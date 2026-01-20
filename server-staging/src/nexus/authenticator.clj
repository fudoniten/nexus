(ns nexus.authenticator
  (:require [nexus.crypto :as crypto]
            [slingshot.slingshot :refer [throw+]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))


(defprotocol ISignatureValidator
  "Protocol for signing messages and validating signatures"
  
  (sign
    [_ signer msg]
    "Signs a message using the key associated with the given signer")
  
  (validate-signature 
    [_ signer msg sig]
    "Validates that a signature matches a message using the signer's key"))

(defrecord Authenticator [key-map verbose]

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
        (let [result (crypto/validate-signature key msg sig)]
          (when verbose
            (println (format "signature for host %s valid: %s" signer result)))
          result)
        (throw+ {:type   ::missing-key
                 :signer signer})))))

(defn- read-key-collection-file
  "Reads a JSON file containing key collection data and returns parsed content with keyword keys"
  [filename]
  (with-open [file (io/reader filename)]
    (json/read file { :key-fn keyword })))

(defn- decode-keys
  "Takes a map of signer keywords to encoded key strings and returns a map of decoded crypto keys"
  [key-col]
  (into {} (for [[signer key] key-col]
             [signer (crypto/decode-key key)])))

(defn make-authenticator
  "Creates a new Authenticator instance from a map of client keys and verbose flag.
   The client-map should contain signer keywords mapping to encoded key strings."
  [client-map verbose]
  (when verbose (println (format "authenticator loading keys for: %s"
                                 (map name (keys client-map)))))
  (Authenticator. (decode-keys client-map) verbose))

(defn initialize-key-collection
  "Initializes an Authenticator by reading keys from a JSON file.
   The file should contain a map of signer names to encoded key strings.
   Returns configured Authenticator instance."
  [filename verbose]
  (-> filename
      (read-key-collection-file)
      (make-authenticator verbose)))
