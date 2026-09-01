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

(defrecord PubkeyAuthenticator [key-map verbose]

  ISignatureValidator

  ;; The server only ever verifies requests signed by a client's private
  ;; key; it never holds a private key itself, so signing is not supported.
  (sign [_ signer _msg]
    (throw+ {:type   ::sign-not-supported
             :signer signer}))

  (validate-signature [_ signer msg sig]
    (let [key (get key-map signer)]
      (if key
        (let [result (crypto/verify-with-public-key key msg sig)]
          (when verbose
            (println (format "pubkey signature for host %s valid: %s" signer result)))
          result)
        (throw+ {:type   ::missing-key
                 :signer signer})))))

(defn- decode-public-keys
  "Takes a map of signer keywords to encoded public key strings and returns a
   map of decoded public keys."
  [key-col]
  (into {} (for [[signer key] key-col]
             [signer (crypto/decode-public-key key)])))

(defn make-pubkey-authenticator
  "Creates a new PubkeyAuthenticator instance from a map of client public keys
   and verbose flag. The client-map should contain signer keywords mapping to
   encoded public key strings."
  [client-map verbose]
  (when verbose (println (format "pubkey authenticator loading keys for: %s"
                                 (map name (keys client-map)))))
  (PubkeyAuthenticator. (decode-public-keys client-map) verbose))

(defn initialize-pubkey-collection
  "Initializes a PubkeyAuthenticator by reading public keys from a JSON file.
   The file should contain a map of signer names to encoded public key
   strings. Unlike the HMAC key-collection file, this file contains no secret
   material and does not need to be kept confidential.
   Returns configured PubkeyAuthenticator instance."
  [filename verbose]
  (-> filename
      (read-key-collection-file)
      (make-pubkey-authenticator verbose)))
