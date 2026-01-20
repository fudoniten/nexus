(ns nexus.client
  "Namespace for Nexus client operations, including sending and receiving requests."
  (:require [fudo-clojure.http.client :as http]
            [fudo-clojure.http.request :as req]
            [fudo-clojure.result :as result]
            [fudo-clojure.common :refer [base64-encode-string instant-to-epoch-timestamp]]
            [nexus.crypto :as crypto]
            [clojure.string :as str])
  (:import javax.crypto.Mac))

;; This namespace is part of a dynamic DNS system called Nexus.
;; The client is responsible for reporting local changes to a Nexus server,
;; such as updating IP addresses and SSHFP records for specific hostnames and domains.

(defn to-path-elem
  "Converts an element to a path string. Supports keywords, strings, and UUIDs."
  [el]
  (cond (keyword? el) (name el)
        (string? el)  el
        (uuid? el)    (str el)
        :else         (throw (ex-info (str "bad path element: " el) {}))))

(defn build-path
  "Builds a URL path from the given elements."
  [& elems]
  (str "/" (str/join "/" (map to-path-elem elems))))

(defn base-request
  "Creates a base HTTP request with the specified server and port."
  [server port]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-option :insecure? true)))

(defn send-ipv4-request
  "Creates a PUT request to send an IPv4 address for a specific hostname and domain."
  [& {:keys [hostname domain server port ip]}]
  (-> (base-request server port)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-path (build-path :api :v2 :domain domain :host hostname :ipv4))))

(defn send-ipv6-request
  "Creates a PUT request to send an IPv6 address for a specific hostname and domain."
  [& {:keys [hostname domain server port ip]}]
  (-> (base-request server port)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-path (build-path :api :v2 :domain domain :host hostname :ipv6))))

(defn send-sshfps-request
  "Creates a PUT request to send SSHFP records for a specific hostname and domain."
  [& {:keys [hostname domain server port sshfps]}]
  (-> (base-request server port)
      (req/as-put)
      (req/with-body (str sshfps))
      (req/with-path (build-path :api :v2 :domain domain :host hostname :sshfps))))

(defn get-ipv4-request
  "Creates a GET request to retrieve the IPv4 address for a specific hostname and domain."
  [& {:keys [hostname domain server port]}]
  (-> (base-request server port)
      (req/as-get)
      (req/with-path (build-path :api :v2 :domain domain :host hostname :ipv4))))

(defn get-ipv6-request
  "Creates a GET request to retrieve the IPv6 address for a specific hostname and domain."
  [& {:keys [hostname domain server port]}]
  (-> (base-request server port)
      (req/as-get)
      (req/with-path (build-path :api :v2 :domain domain :host hostname :ipv6))))

(defn get-sshfps-request
  "Creates a GET request to retrieve SSHFP records for a specific hostname and domain."
  [& {:keys [hostname domain server port]}]
  (-> (base-request server port)
      (req/as-get)
      (req/with-path (build-path :api :v2 :domain domain :host hostname :sshfps))))

(defn make-signature-generator
  "Creates a function to generate HMAC signatures using the provided key."
  [hmac-key-str]
  (let [hmac-key (crypto/decode-key hmac-key-str)]
    (fn [msg]
      (let [hmac (doto (Mac/getInstance (.getAlgorithm hmac-key))
                   (.init hmac-key))]
        (-> (.doFinal hmac (.getBytes msg))
            (base64-encode-string))))))

(defn pthru [o] (clojure.pprint/pprint o) o)

(defn make-request-authenticator
  "Creates a request authenticator function using HMAC for signing requests."
  [{hmac-key ::hmac-key hostname ::hostname}]
  (let [sign (make-signature-generator hmac-key)]
    (fn [req]
      (let [timestamp    (-> req (req/timestamp))]
        (when (nil? timestamp)
          (throw (ex-info "Timestamp is nil" {:type ::nil-timestamp})))
        (let [timestamp-str (-> timestamp (instant-to-epoch-timestamp) (str))
              req-str (str (-> req (req/method)       (name))
                           (-> req (req/request-path) (str/replace #"\?$" ""))
                           timestamp-str
                           (-> req (req/body)))
            sig     (sign req-str)]
        (req/with-headers req
          {:access-signature sig
           :access-timestamp timestamp
           :access-hostname  hostname}))))))

(defprotocol INexusClient
  "Protocol defining the operations for a Nexus client."
  (send-ipv4!     [_ ipv4])
  (send-ipv6!     [_ ipv6])
  (send-sshfps!   [_ sshfps])
  (get-ipv4!      [_])
  (get-ipv6!      [_])
  (get-sshfps!    [_])
  (switch-server! [_]))

(defn rotate
  "Takes a collection and rotates it n steps."
  ([coll] (rotate coll 1))
  ([coll n]
   (let [new-head (drop n coll)
         new-tail (take n coll)]
     (concat new-head new-tail))))

(defn exec!
  "Executes an HTTP request using the provided client. Logs the request if verbose is true."
  [client verbose req]
  (when verbose
    (println (str "outgoing " (req/method req)
                  " request to " (req/host req)
                  ": " (req/request-path req))))
  (result/send-failure (http/execute-request! client req)
                       (fn [e]
                         (when verbose (println e)))))

(defn make-nexus-client
  "Creates a Nexus client with the specified configuration."
  [& { :keys [http-client servers port domain hostnames verbose]
                              :or   {verbose false}}]
  (let [server-rank    (atom servers)
        rotate-server! (fn [] (swap! server-rank rotate))
        get-server     (fn [] (first @server-rank))
        base-req       (fn [hostname]
                         {:server   (get-server)
                          :port     port
                          :domain   domain
                          :hostname hostname})]
    (reify
      INexusClient
      (send-ipv4! [_ ipv4]
        (doseq [hostname hostnames]
          (exec! http-client verbose (send-ipv4-request (assoc (base-req hostname) :ip ipv4)))))
      (send-ipv6! [_ ipv6]
        (doseq [hostname hostnames]
          (exec! http-client verbose (send-ipv6-request (assoc (base-req hostname) :ip ipv6)))))
      (send-sshfps! [_ sshfps]
        (doseq [hostname hostnames]
          (exec! http-client verbose (send-sshfps-request (assoc (base-req hostname) :sshfps sshfps)))))

      (get-ipv4! [_]
        (exec! http-client verbose (get-ipv4-request (base-req (first hostnames)))))
      (get-ipv6! [_]
        (exec! http-client verbose (get-ipv6-request (base-req (first hostnames)))))
      (get-sshfps! [_]
        (exec! http-client verbose (get-sshfps-request (base-req (first hostnames)))))

      (switch-server! [_]
        (rotate-server!)
        (when verbose
          (println (str "Switched to server: " (get-server))))))))

(defn connect
  "Establishes a connection to the Nexus server with the given configuration."
  [& {:keys [domain aliases hostname servers port hmac-key logger verbose]}]
  (let [authenticator (make-request-authenticator {::hmac-key hmac-key ::hostname hostname})]
    (make-nexus-client :http-client (http/json-client :authenticator   authenticator
                                                      :logger          logger)
                       :servers     servers
                       :port        port
                       :domain      domain
                       :hostnames   (concat [hostname] aliases)
                       :verbose     verbose)))

(defn combine-nexus-clients
  "Combines multiple Nexus clients into a single client that can send requests to all."
  [clients]
  (reify INexusClient
    (send-ipv4!   [_ ip]     (doseq [client clients] (send-ipv4! client ip)))
    (send-ipv6!   [_ ip]     (doseq [client clients] (send-ipv6! client ip)))
    (send-sshfps! [_ sshfps] (doseq [client clients] (send-sshfps! client sshfps)))

    (get-ipv4!    [_]        (throw (ex-info "Not implemented" {:type ::not-implemented})))
    (get-ipv6!    [_]        (throw (ex-info "Not implemented" {:type ::not-implemented})))
    (get-sshfps!  [_]        (throw (ex-info "Not implemented" {:type ::not-implemented})))))
