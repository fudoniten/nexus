(ns nexus.authenticator-test
  (:require [clojure.test :refer [deftest is testing]]
            [nexus.authenticator :as auth]
            [nexus.crypto :as crypto]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(deftest test-make-authenticator
  (testing "Creating authenticator from client map"
    (let [key1 (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          key2 (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          client-map {:host1 key1 :host2 key2}
          authenticator (auth/make-authenticator client-map false)]
      (is (instance? nexus.authenticator.Authenticator authenticator)))))

(deftest test-sign
  (testing "Signing a message"
    (let [key (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          authenticator (auth/make-authenticator {:test-host key} false)
          message "test message"
          signature (auth/sign authenticator :test-host message)]
      (is (string? signature))
      (is (pos? (count signature))))))

(deftest test-sign-missing-key
  (testing "Signing with missing key throws exception"
    (let [authenticator (auth/make-authenticator {:host1 (crypto/encode-key (crypto/generate-key "HmacSHA512"))} false)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/sign authenticator :nonexistent-host "message"))))))

(deftest test-validate-signature
  (testing "Validating correct signature"
    (let [key (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          authenticator (auth/make-authenticator {:test-host key} false)
          message "test message"
          signature (auth/sign authenticator :test-host message)]
      (is (true? (auth/validate-signature authenticator :test-host message signature)))))
  
  (testing "Validating incorrect signature"
    (let [key (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          authenticator (auth/make-authenticator {:test-host key} false)
          message "test message"
          signature (auth/sign authenticator :test-host message)]
      (is (false? (auth/validate-signature authenticator :test-host "different message" signature))))))

(deftest test-validate-signature-missing-key
  (testing "Validating signature with missing key throws exception"
    (let [authenticator (auth/make-authenticator {:host1 (crypto/encode-key (crypto/generate-key "HmacSHA512"))} false)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/validate-signature authenticator :nonexistent-host "message" "signature"))))))

(deftest test-initialize-key-collection
  (testing "Initializing authenticator from JSON file"
    (let [temp-file (java.io.File/createTempFile "test-keys" ".json")
          key1 (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          key2 (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          keys-map {:host1 key1 :host2 key2}]
      (try
        ;; Write test keys to file
        (with-open [writer (io/writer temp-file)]
          (json/write keys-map writer))
        
        ;; Load authenticator from file
        (let [authenticator (auth/initialize-key-collection (.getPath temp-file) false)
              message "test"
              sig1 (auth/sign authenticator :host1 message)
              sig2 (auth/sign authenticator :host2 message)]
          (is (instance? nexus.authenticator.Authenticator authenticator))
          (is (true? (auth/validate-signature authenticator :host1 message sig1)))
          (is (true? (auth/validate-signature authenticator :host2 message sig2)))
          (is (false? (auth/validate-signature authenticator :host1 message sig2))))
        (finally
          (.delete temp-file))))))

(deftest test-verbose-mode
  (testing "Verbose mode prints debug info"
    (let [key (crypto/encode-key (crypto/generate-key "HmacSHA512"))
          output (with-out-str
                   (let [authenticator (auth/make-authenticator {:test-host key} true)
                         message "test"
                         sig (auth/sign authenticator :test-host message)]
                     (auth/validate-signature authenticator :test-host message sig)))]
      (is (re-find #"signature for host :test-host valid: true" output)))))
