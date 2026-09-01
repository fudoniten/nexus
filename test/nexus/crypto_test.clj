(ns nexus.crypto-test
  (:require [clojure.test :refer [deftest is testing]]
            [nexus.crypto :as crypto]))

(deftest test-generate-key
  (testing "Key generation with default algorithm"
    (is (instance? javax.crypto.SecretKey (crypto/generate-key "HmacSHA512"))))
  (testing "Key generation with seed"
    (is (instance? javax.crypto.SecretKey (crypto/generate-key "HmacSHA512" "seed")))))

(deftest test-encode-decode-key
  (let [key (crypto/generate-key "HmacSHA512")
        encoded (crypto/encode-key key)
        decoded (crypto/decode-key encoded)]
    (testing "Encoding and decoding key"
      (is (= (.getAlgorithm key) (.getAlgorithm decoded)))
      (is (= (seq (.getEncoded key)) (seq (.getEncoded decoded)))))))

(deftest test-generate-signature
  (let [key (crypto/generate-key "HmacSHA512")
        data "test data"
        signature (crypto/generate-signature key data)]
    (testing "Signature generation"
      (is (string? signature)))))

(deftest test-validate-signature
  (let [key (crypto/generate-key "HmacSHA512")
        data "test data"
        signature (crypto/generate-signature key data)]
    (testing "Signature validation"
      (is (crypto/validate-signature key data signature))
      (is (not (crypto/validate-signature key "different data" signature))))))

(deftest test-generate-keypair
  (testing "Keypair generation"
    (let [{:keys [public-key private-key]} (crypto/generate-keypair)]
      (is (instance? java.security.PublicKey public-key))
      (is (instance? java.security.PrivateKey private-key)))))

(deftest test-encode-decode-public-key
  (let [{:keys [public-key]} (crypto/generate-keypair)
        encoded (crypto/encode-public-key public-key)
        decoded (crypto/decode-public-key encoded)]
    (testing "Encoding and decoding a public key"
      (is (string? encoded))
      (is (= (seq (.getEncoded public-key)) (seq (.getEncoded decoded)))))))

(deftest test-encode-decode-private-key
  (let [{:keys [private-key]} (crypto/generate-keypair)
        encoded (crypto/encode-private-key private-key)
        decoded (crypto/decode-private-key encoded)]
    (testing "Encoding and decoding a private key"
      (is (string? encoded))
      (is (= (seq (.getEncoded private-key)) (seq (.getEncoded decoded)))))))

(deftest test-sign-and-verify-with-keypair
  (let [{:keys [public-key private-key]} (crypto/generate-keypair)
        data "test data"
        signature (crypto/sign-with-private-key private-key data)]
    (testing "Signature generation"
      (is (string? signature)))
    (testing "Signature verification"
      (is (crypto/verify-with-public-key public-key data signature))
      (is (not (crypto/verify-with-public-key public-key "different data" signature))))
    (testing "A different keypair's public key does not verify"
      (let [{other-public :public-key} (crypto/generate-keypair)]
        (is (not (crypto/verify-with-public-key other-public data signature)))))))

(deftest test-verify-with-public-key-rejects-malformed-signature
  (testing "A malformed signature (wrong shape, not just wrong bytes) is
            rejected as invalid rather than throwing -- this matters because
            the signature is attacker-controlled request input"
    (let [{:keys [public-key]} (crypto/generate-keypair)
          data "test data"
          ;; A base64-encoded 64-byte HMAC-SHA512 output happens to be the
          ;; same length as an Ed25519 signature, but is not valid Ed25519
          ;; signature content -- this is exactly what an unmigrated v2
          ;; client's HMAC signature looks like if it's ever fed to the v3
          ;; verifier.
          garbage-sig (crypto/generate-signature (crypto/generate-key "HmacSHA512") data)]
      (is (false? (crypto/verify-with-public-key public-key data garbage-sig)))))
  (testing "Non-base64 garbage is also rejected rather than throwing"
    (let [{:keys [public-key]} (crypto/generate-keypair)]
      (is (false? (crypto/verify-with-public-key public-key "test data" "not valid base64!!"))))))

(deftest test-keypair-roundtrip-through-encoding
  (testing "A keypair signs/verifies correctly after an encode/decode roundtrip"
    (let [{:keys [public-key private-key]} (crypto/generate-keypair)
          encoded-public  (crypto/encode-public-key public-key)
          encoded-private (crypto/encode-private-key private-key)
          decoded-public  (crypto/decode-public-key encoded-public)
          decoded-private (crypto/decode-private-key encoded-private)
          data "test data"
          signature (crypto/sign-with-private-key decoded-private data)]
      (is (crypto/verify-with-public-key decoded-public data signature)))))
