(ns nexus.client
  (:require [fudo-clojure.http.client :as http]
            [fudo-clojure.http.request :as req]
            [fudo-clojure.common :refer [base64-encode-string instant-to-epoch-timestamp]]
            [nexus.crypto :as crypto]
            [clojure.string :as str])
  (:import javax.crypto.Mac))

(defn- to-path-elem [el]
  (cond (keyword? el) (name el)
        (string? el)  el
        :else         (throw (ex-info (str "bad path element: " el) {}))))

(defn- build-path [& elems]
  (str "/" (str/join "/" (map to-path-elem elems))))

(defn- send-ipv4-request [hostname server port ip]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :ipv4))))

(defn- send-ipv6-request [hostname server port ip]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body (str ip))
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :ipv6))))

(defn- send-sshfps-request [hostname server port sshfps]
  (-> (req/base-request)
      (req/as-put)
      (req/with-body sshfps)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :sshfps))))

(defn- get-ipv4-request [hostname server port]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :ipv4))))

(defn- get-ipv6-request [hostname server port]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :ipv6))))

(defn- get-sshfps-request [hostname server port]
  (-> (req/base-request)
      (req/as-get)
      (req/with-host server)
      (req/with-port port)
      (req/with-path (build-path :api hostname :sshfps))))

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

(defrecord NexusClient [http-client hostname server port]

  INexusClient

  (send-ipv4! [_ ipv4]
    (http/execute-request! http-client (send-ipv4-request hostname server port ipv4)))

  (send-ipv6! [_ ipv6]
    (http/execute-request! http-client (send-ipv6-request hostname server port ipv6)))

  (send-sshfps! [_ sshfps]
    (http/execute-request! http-client (send-sshfps-request hostname server port sshfps)))

  (get-ipv4!   [_] (http/execute-request! http-client (get-ipv4-request hostname server port)))
  (get-ipv6!   [_] (http/execute-request! http-client (get-ipv6-request hostname server port)))
  (get-sshfps! [_] (http/execute-request! http-client (get-sshfps-request hostname server port))))

(defn connect [& {:keys [hostname server port hmac-key]
                  :or   {port 80}}]
  (let [authenticator (make-request-authenticator {::hmac-key hmac-key ::hostname hostname})]
    (NexusClient. (http/json-client :authenticator authenticator)
                  hostname
                  server
                  port)))
