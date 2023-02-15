(ns nexus.crypto
  (:import java.security.SecureRandom
           (javax.crypto Mac KeyGenerator)
           javax.crypto.spec.SecretKeySpec
           java.util.Base64))

(defn- generate-key-impl [algo rng]
  (let [gen (doto (KeyGenerator/getInstance algo) (.init rng))]
    (.generateKey gen)))

(defn generate-key
  ([algo]      (generate-key-impl algo (SecureRandom.)))
  ([algo seed] (let [rng (-> seed
                             (.getBytes)
                             (SecureRandom.))]
                 (generate-key-impl algo rng))))

(defn encode-key [key]
  (let [encoded-key (.encodeToString (Base64/getEncoder)
                                     (.getEncoded key))]
    (format "%s:%s" (.getAlgorithm key) encoded-key)))

(defn decode-key [key-str]
  (let [[algo encoded-key] (.split key-str ":" 2)
        key-bytes (.decode (Base64/getDecoder) encoded-key)]
    (SecretKeySpec. key-bytes algo)))

(defn generate-signature [key data]
  (let [algo (.getAlgorithm key)
        mac (doto (Mac/getInstance algo)
              (.init key)
              (.update (.getBytes data)))]
    (.encodeToString (Base64/getEncoder) (.doFinal mac))))

(defn validate-signature [key data sig]
  (let [local-sig (generate-signature key data)]
    (.equals sig local-sig)))
