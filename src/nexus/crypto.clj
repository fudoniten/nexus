(ns nexus.crypto
  "Cryptographic utilities for key generation, encoding, and signature operations."
  (:require [clojure.tools.logging :as log])
  (:import java.security.SecureRandom
           (javax.crypto Mac KeyGenerator)
           (javax.crypto.spec SecretKeySpec)
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
