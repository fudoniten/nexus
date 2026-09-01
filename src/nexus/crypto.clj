(ns nexus.crypto
  "Cryptographic utilities for key generation, encoding, and signature operations."
  (:require [clojure.tools.logging :as log])
  (:import java.security.SecureRandom
           (javax.crypto Mac KeyGenerator)
           (javax.crypto.spec SecretKeySpec)
           (java.security KeyPairGenerator KeyFactory Signature PublicKey PrivateKey)
           (java.security.spec X509EncodedKeySpec PKCS8EncodedKeySpec)
           java.util.Base64))

(def ^:private key-generator-thread-local
  "Thread-local storage for KeyGenerator instances to ensure thread safety."
  (ThreadLocal.))

(defn generate-key-impl
  "Generates a cryptographic key using the specified algorithm and random number generator (rng)."
  [algo rng]
  (log/debug "Generating key with algorithm:" algo)
  (try
    (let [gen (or (.get key-generator-thread-local)
                  (doto (KeyGenerator/getInstance algo)
                    (.init rng)))]
      (.set key-generator-thread-local gen)
      (.generateKey gen))
    (catch Exception e
      (throw (ex-info "Failed to generate cryptographic key" {:algorithm algo} e)))))

(defn generate-key
  "Generates a cryptographic key using the specified algorithm.
  Optionally accepts a seed for the random number generator."
  ([algo]
   (generate-key-impl algo (SecureRandom.)))
  ([algo seed]
   (let [rng (-> seed
                 (.getBytes)
                 (SecureRandom.))]
     (generate-key-impl algo rng))))

(defn encode-key
  "Encodes a cryptographic key into a Base64 string with its algorithm."
  [key]
  (try
    (let [encoded-key (.encodeToString (Base64/getEncoder)
                                       (.getEncoded key))]
      (format "%s:%s" (.getAlgorithm key) encoded-key))
    (catch Exception e
      (throw (ex-info "Failed to encode cryptographic key" {:key key} e)))))

(defn decode-key
  "Decodes a Base64 encoded key string back into a SecretKeySpec object."
  [key-str]
  (try
    (let [[algo encoded-key] (.split key-str ":" 2)
          key-bytes (.decode (Base64/getDecoder) encoded-key)]
      (SecretKeySpec. key-bytes algo))
    (catch Exception e
      (throw (ex-info "Failed to decode cryptographic key" {:key-str key-str} e)))))

(defn generate-signature
  "Generates a Base64 encoded signature for the given data using the specified key."
  [key data]
  (try
    (let [algo (.getAlgorithm key)
          mac (doto (Mac/getInstance algo)
                (.init key)
                (.update (.getBytes data)))]
      (.encodeToString (Base64/getEncoder) (.doFinal mac)))
    (catch Exception e
      (throw (ex-info "Failed to generate signature" {:key key :data data} e)))))

(defn validate-signature
  "Validates a signature by comparing it with a locally generated one for the given data and key."
  [key data sig]
  (try
    (let [local-sig (generate-signature key data)]
      (.equals sig local-sig))
    (catch Exception e
      (throw (ex-info "Failed to validate signature" {:key key :data data :signature sig} e)))))

;; --- Asymmetric (Ed25519) keypairs ---
;;
;; Used for public-key request authentication: a host signs requests with its
;; private key, and the server verifies them with the corresponding public
;; key. Unlike the HMAC keys above, only the private key is sensitive -- the
;; public key can be distributed and stored in the clear.
;;
;; Ed25519 is used rather than RSA/ECDSA because it's deterministic (no
;; per-signature RNG to get wrong), has tiny keys/signatures, and has been
;; natively supported by the JDK's default security providers since Java 15
;; (JEP 339), so no extra dependency is required.

(def ^:private asymmetric-algorithm "Ed25519")

(defn generate-keypair
  "Generates an Ed25519 keypair, returning {:public-key ... :private-key ...}."
  []
  (try
    (let [generator (KeyPairGenerator/getInstance asymmetric-algorithm)
          keypair   (.generateKeyPair generator)]
      {:public-key  (.getPublic keypair)
       :private-key (.getPrivate keypair)})
    (catch Exception e
      (throw (ex-info "Failed to generate keypair" {:algorithm asymmetric-algorithm} e)))))

(defn encode-public-key
  "Encodes a public key into a Base64 string with its algorithm."
  [^PublicKey public-key]
  (try
    (format "%s:%s" asymmetric-algorithm
            (.encodeToString (Base64/getEncoder) (.getEncoded public-key)))
    (catch Exception e
      (throw (ex-info "Failed to encode public key" {} e)))))

(defn decode-public-key
  "Decodes a Base64 encoded public key string back into a PublicKey object."
  [key-str]
  (try
    (let [[_algo encoded-key] (.split key-str ":" 2)
          key-bytes (.decode (Base64/getDecoder) encoded-key)
          factory   (KeyFactory/getInstance asymmetric-algorithm)]
      (.generatePublic factory (X509EncodedKeySpec. key-bytes)))
    (catch Exception e
      (throw (ex-info "Failed to decode public key" {:key-str key-str} e)))))

(defn encode-private-key
  "Encodes a private key into a Base64 string with its algorithm."
  [^PrivateKey private-key]
  (try
    (format "%s:%s" asymmetric-algorithm
            (.encodeToString (Base64/getEncoder) (.getEncoded private-key)))
    ;; Deliberately excludes the key material from ex-info on failure.
    (catch Exception e
      (throw (ex-info "Failed to encode private key" {} e)))))

(defn decode-private-key
  "Decodes a Base64 encoded private key string back into a PrivateKey object."
  [key-str]
  (try
    (let [[_algo encoded-key] (.split key-str ":" 2)
          key-bytes (.decode (Base64/getDecoder) encoded-key)
          factory   (KeyFactory/getInstance asymmetric-algorithm)]
      (.generatePrivate factory (PKCS8EncodedKeySpec. key-bytes)))
    ;; Deliberately excludes the key material and the source string from
    ;; ex-info on failure, since key-str carries encoded private key bytes.
    (catch Exception e
      (throw (ex-info "Failed to decode private key" {} e)))))

(defn sign-with-private-key
  "Generates a Base64 encoded Ed25519 signature for the given data using the private key."
  [^PrivateKey private-key data]
  (try
    (let [signer (doto (Signature/getInstance asymmetric-algorithm)
                   (.initSign private-key)
                   (.update (.getBytes data "UTF-8")))]
      (.encodeToString (Base64/getEncoder) (.sign signer)))
    (catch Exception e
      (throw (ex-info "Failed to generate signature" {:data data} e)))))

(defn verify-with-public-key
  "Validates a Base64 encoded Ed25519 signature for the given data using the
  public key. Returns false -- rather than throwing -- for a malformed or
  otherwise invalid signature. sig is attacker-controlled request input, and
  Signature/verify can throw (e.g. SignatureException: s is too large) on
  bytes that are simply the wrong shape to be a signature at all; from the
  caller's perspective that's just an invalid signature, not a server error,
  so it must not be allowed to propagate as an unhandled exception."
  [^PublicKey public-key data sig]
  (try
    (let [sig-bytes (.decode (Base64/getDecoder) sig)
          verifier  (doto (Signature/getInstance asymmetric-algorithm)
                      (.initVerify public-key)
                      (.update (.getBytes data "UTF-8")))]
      (.verify verifier sig-bytes))
    (catch Exception _
      false)))
