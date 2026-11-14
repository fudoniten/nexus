(ns nexus.authenticator-test
  (:require [clojure.test :refer [deftest is testing]]
            [nexus.authenticator :as auth]
            [nexus.crypto :as crypto]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

;; --- PubkeyAuthenticator ---

(defn- gen-encoded-keypair []
  (let [{:keys [public-key private-key]} (crypto/generate-keypair)]
    {:public  (crypto/encode-public-key public-key)
     :private private-key}))

(deftest test-make-pubkey-authenticator
  (testing "Creating a pubkey authenticator from a client map"
    (let [{pub1 :public} (gen-encoded-keypair)
          {pub2 :public} (gen-encoded-keypair)
          client-map {:host1 pub1 :host2 pub2}
          authenticator (auth/make-pubkey-authenticator client-map false)]
      (is (instance? nexus.authenticator.PubkeyAuthenticator authenticator)))))

(deftest test-pubkey-sign-not-supported
  (testing "Signing via a PubkeyAuthenticator always throws"
    (let [{pub :public} (gen-encoded-keypair)
          authenticator (auth/make-pubkey-authenticator {:test-host pub} false)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/sign authenticator :test-host "message"))))))

(deftest test-pubkey-validate-signature
  (testing "Validating a correct Ed25519 signature"
    (let [{pub :public priv :private} (gen-encoded-keypair)
          authenticator (auth/make-pubkey-authenticator {:test-host pub} false)
          message "test message"
          signature (crypto/sign-with-private-key priv message)]
      (is (true? (auth/validate-signature authenticator :test-host message signature)))))

  (testing "Validating an incorrect Ed25519 signature"
    (let [{pub :public priv :private} (gen-encoded-keypair)
          authenticator (auth/make-pubkey-authenticator {:test-host pub} false)
          message "test message"
          signature (crypto/sign-with-private-key priv message)]
      (is (false? (auth/validate-signature authenticator :test-host "different message" signature)))))

  (testing "Validating a signature made with a different host's private key"
    (let [{pub1 :public} (gen-encoded-keypair)
          {priv2 :private} (gen-encoded-keypair)
          authenticator (auth/make-pubkey-authenticator {:test-host pub1} false)
          message "test message"
          signature (crypto/sign-with-private-key priv2 message)]
      (is (false? (auth/validate-signature authenticator :test-host message signature))))))

(deftest test-pubkey-validate-signature-missing-key
  (testing "Validating with a missing key throws"
    (let [{pub :public} (gen-encoded-keypair)
          authenticator (auth/make-pubkey-authenticator {:host1 pub} false)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (auth/validate-signature authenticator :nonexistent-host "message" "signature"))))))

(deftest test-initialize-pubkey-collection
  (testing "Initializing a pubkey authenticator from a JSON file"
    (let [temp-file (java.io.File/createTempFile "test-pubkeys" ".json")
          {pub1 :public priv1 :private} (gen-encoded-keypair)
          {pub2 :public priv2 :private} (gen-encoded-keypair)
          keys-map {:host1 pub1 :host2 pub2}]
      (try
        (with-open [writer (io/writer temp-file)]
          (json/write keys-map writer))

        (let [authenticator (auth/initialize-pubkey-collection (.getPath temp-file) false)
              message "test"
              sig1 (crypto/sign-with-private-key priv1 message)
              sig2 (crypto/sign-with-private-key priv2 message)]
          (is (instance? nexus.authenticator.PubkeyAuthenticator authenticator))
          (is (true? (auth/validate-signature authenticator :host1 message sig1)))
          (is (true? (auth/validate-signature authenticator :host2 message sig2)))
          (is (false? (auth/validate-signature authenticator :host1 message sig2))))
        (finally
          (.delete temp-file))))))
