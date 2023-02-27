(ns nexus.client
  (:require [fudo-clojure.http.client :as http]
            [fudo-clojure.http.request :as req]
            [fudo-clojure.common :refer [base64-encode-string instant-to-epoch-timestamp]]
            [fudo-clojure.logging :refer [error!]]
            [nexus.crypto :as crypto]
            [clojure.string :as str]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import javax.crypto.Mac))

(defn- to-path-elem [el]
  (cond (keyword? el) (name el)
        (string? el)  el
        :else         (throw (ex-info (str "bad path element: " el) {}))))

(defn- build-path [& elems]
  (str "/" (str/join "/" (map to-path-elem elems))))

(defn- send-ipv4-request
  [& {:keys [hostname domain server port ip]}]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :ipv4))))

(defn- send-ipv6-request
  [& {:keys [hostname domain server port ip]}]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :ipv6))))

(defn- send-sshfps-request
  [& {:keys [hostname domain server port sshfps]}]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body sshfps)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :sshfps))))

(defn- get-ipv4-request
  [& {:keys [hostname domain server port]}]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :ipv4))))

(defn- get-ipv6-request
  [& {:keys [hostname domain server port]}]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :ipv6))))

(defn- get-sshfps-request
  [& {:keys [hostname domain server port]}]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api domain hostname :sshfps))))

(defn- make-signature-generator [hmac-key-str]
  (let [hmac-key (crypto/decode-key hmac-key-str)
        hmac (doto (Mac/getInstance (.getAlgorithm hmac-key))
               (.init hmac-key))]
    (fn [msg]
      (-> (.doFinal hmac (.getBytes msg))
          (base64-encode-string)))))

(defn- make-request-authenticator
  [{hmac-key ::hmac-key hostname ::hostname}]
  (let [sign (make-signature-generator hmac-key)]
    (fn [req]
      (let [timestamp    (-> req (req/timestamp) (instant-to-epoch-timestamp) (str))
            req-str (str (-> req (req/method) (name))
                         (-> req (req/uri))
                         timestamp
                         (-> req (req/body)))
            sig     (sign req-str)]
        (req/with-headers req
          {:access-signature sig
           :access-timestamp timestamp
           :access-hostname  hostname})))))

(defprotocol INexusClient
  (send-ipv4!   [_ ipv4])
  (send-ipv6!   [_ ipv6])
  (send-sshfps! [_ sshfps])
  (get-ipv4!    [_])
  (get-ipv6!    [_])
  (get-sshfps!  [_]))

(defn- exec! [{:keys [logger] :as client} req]
  (try+ (http/execute-request! client req)
        (catch Exception e
          (error! logger (str e)))))

(defn- make-nexus-client [& { :keys [http-client server port domain hostname] }]
  (let [base-req {:server server :port port :domain domain :hostname hostname}]
    (reify INexusClient
      (send-ipv4! [_ ipv4]
        (exec! http-client (send-ipv4-request (assoc base-req :ip ipv4))))
      (send-ipv6! [_ ipv6]
        (exec! http-client (send-ipv6-request (assoc base-req :ip ipv6))))
      (send-sshfps! [_ sshfps]
        (exec! http-client (send-sshfps-request (assoc base-req :sshfps sshfps))))

      (get-ipv4! [_]
        (exec! http-client (get-ipv4-request base-req)))
      (get-ipv6! [_]
        (exec! http-client (get-ipv6-request base-req)))
      (get-sshfps! [_]
        (exec! http-client (get-sshfps-request base-req))))))

(defn connect
  [& {:keys [domain hostname server port hmac-key logger]
      :or   {port 80}}]
  (let [authenticator (make-request-authenticator {::hmac-key hmac-key ::hostname hostname})]
    (make-nexus-client :http-client (http/json-client :authenticator authenticator :logger logger)
                       :server server
                       :port port
                       :domain domain
                       :hostname hostname)))

(defn combine-nexus-clients [clients]
  (reify INexusClient
    (send-ipv4!   [_ ip]     (map #(send-ipv4! % ip) clients))
    (send-ipv6!   [_ ip]     (map #(send-ipv6! % ip) clients))
    (send-sshfps! [_ sshfps] (map #(send-sshfps! % sshfps) clients))

    (get-ipv4!    [_]        (throw+ {:type ::not-implemented}))
    (get-ipv6!    [_]        (throw+ {:type ::not-implemented}))
    (get-sshfps!  [_]        (throw+ {:type ::not-implemented}))))
