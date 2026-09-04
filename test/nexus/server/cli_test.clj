(ns nexus.server.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [nexus.server.cli :as cli]))

(defn- temp-file
  "Creates a temporary file that exists on disk, for the file-existence checks
  validate-config makes."
  []
  (doto (java.io.File/createTempFile "nexus-cli-test" ".json")
    (.deleteOnExit)))

(defn- base-config
  "The non-key options validate-config also inspects, so each test can vary
  only the key options it cares about."
  []
  {:host-alias-map (.getPath (temp-file))})

(deftest host-keys-satisfied-by-either-kind
  (let [hmac   (.getPath (temp-file))
        pubkey (.getPath (temp-file))]
    (testing "an HMAC key file alone satisfies the host requirement"
      (is (nil? (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys hmac
                                                     :challenge-keys hmac))))))

    (testing "a public key file alone satisfies it too, so a fully migrated
              deployment needs no host HMAC secret on the server"
      (is (nil? (:errors (cli/validate-config (assoc (base-config)
                                                     :host-public-keys pubkey
                                                     :challenge-keys hmac))))))

    (testing "both together are accepted, which is the migration window"
      (is (nil? (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys hmac
                                                     :host-public-keys pubkey
                                                     :challenge-keys hmac))))))))

(deftest challenge-keys-satisfied-by-either-kind
  (let [hmac   (.getPath (temp-file))
        pubkey (.getPath (temp-file))]
    (testing "challenge public keys alone satisfy the challenge requirement"
      (is (nil? (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys hmac
                                                     :challenge-public-keys pubkey))))))

    (testing "challenge HMAC keys alone still do"
      (is (nil? (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys hmac
                                                     :challenge-keys hmac))))))))

(deftest missing-keys-rejected
  (let [hmac (.getPath (temp-file))]
    (testing "neither kind of host key is an error"
      (let [errors (:errors (cli/validate-config (assoc (base-config)
                                                        :challenge-keys hmac)))]
        (is (seq errors))
        (is (some #(re-find #"no host keys given" %) errors))))

    (testing "neither kind of challenge key is an error"
      (let [errors (:errors (cli/validate-config (assoc (base-config)
                                                        :host-keys hmac)))]
        (is (seq errors))
        (is (some #(re-find #"no challenge keys given" %) errors))))))

(deftest named-key-files-must-exist
  (let [hmac    (.getPath (temp-file))
        missing (.getPath (io/file (System/getProperty "java.io.tmpdir")
                                   "nexus-cli-test-does-not-exist.json"))]
    (testing "a host key file that was given but does not exist is an error"
      (is (some #(= "host-keys file does not exist" %)
                (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys missing
                                                     :challenge-keys hmac))))))

    (testing "so is a missing challenge public key file"
      (is (some #(= "challenge-public-keys file does not exist" %)
                (:errors (cli/validate-config (assoc (base-config)
                                                     :host-keys hmac
                                                     :challenge-public-keys missing))))))))
