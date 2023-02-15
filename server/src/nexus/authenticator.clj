(ns nexus.authenticator
  (:require [nexus.crypto :as crypto]
            [slingshot.slingshot :refer [throw+]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))


(defprotocol ISignatureValidator
  (sign [_ signer msg])
  (validate-signature [_ signer msg sig]))

(defrecord Authenticator [key-map]

  ISignatureValidator

  (sign [_ signer msg]
    (let [key (get key-map signer)]
      (if key
        (crypto/generate-signature key msg)
        (throw+ {:type   ::missing-key
                 :signer signer}))))

  (validate-signature [_ signer msg sig]
    (let [key (get key-map signer)]
      (if key
        (crypto/validate-signature key msg sig)
        (throw+ {:type   ::missing-key
                 :signer signer})))))

(defn- read-key-collection-file [filename]
  (with-open [file (io/reader filename)]
    (json/read file { :key-fn keyword })))

(defn- convert-keys [key-col]
  (into {} (for [[signer key] key-col]
             [signer (crypto/decode-key key)])))

(defn read-key-collection [filename]
  (Authenticator. (-> filename
                      (read-key-collection-file)
                      (convert-keys))))
