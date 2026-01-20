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
