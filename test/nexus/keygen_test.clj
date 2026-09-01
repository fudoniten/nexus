(ns nexus.keygen-test
  (:require [clojure.test :refer [deftest is testing]]
            [nexus.keygen :as keygen]
            [nexus.crypto :as crypto]
            [clojure.java.io :as io]))

(deftest test-gen-key
  (testing "Key generation with default algorithm"
    (is (instance? javax.crypto.SecretKey (keygen/gen-key {:algorithm "HmacSHA512"}))))
  (testing "Key generation with seed"
    (is (instance? javax.crypto.SecretKey (keygen/gen-key {:algorithm "HmacSHA512" :seed "seed"})))))

(deftest test-write-key
  (let [key (crypto/generate-key "HmacSHA512")
        filename "test-write-key.txt"]
    (testing "Writing key to file"
      (try
        (keygen/write-key {:key key :filename filename})
        (is (.exists (io/file filename)))
        (finally
          (io/delete-file filename true))))))

(deftest test-main
  (testing "Main function with verbose flag"
    (try
      (with-out-str
        (keygen/-main "-v" "-a" "HmacSHA512" "test-main-key.txt"))
      (is (.exists (io/file "test-main-key.txt")))
      (finally
        (io/delete-file "test-main-key.txt" true)))))

(deftest test-write-keypair
  (let [keypair (crypto/generate-keypair)
        filename "test-write-keypair.key"
        pub-filename "test-write-keypair.key.pub"]
    (testing "Writing a keypair to file writes both private and public files"
      (try
        (keygen/write-keypair {:keypair keypair :filename filename})
        (is (.exists (io/file filename)))
        (is (.exists (io/file pub-filename)))
        (testing "the files roundtrip through decode"
          (let [decoded-priv (crypto/decode-private-key (slurp filename))
                decoded-pub  (crypto/decode-public-key (slurp pub-filename))]
            (is (= (seq (.getEncoded (:private-key keypair))) (seq (.getEncoded decoded-priv))))
            (is (= (seq (.getEncoded (:public-key keypair))) (seq (.getEncoded decoded-pub))))))
        (testing "the private key file is restricted to owner-only permissions"
          (let [perms (java.nio.file.Files/getPosixFilePermissions (.toPath (io/file filename))
                                                                     (into-array java.nio.file.LinkOption []))]
            (is (= #{java.nio.file.attribute.PosixFilePermission/OWNER_READ
                     java.nio.file.attribute.PosixFilePermission/OWNER_WRITE}
                   (set perms)))))
        (finally
          (io/delete-file filename true)
          (io/delete-file pub-filename true))))))

(deftest test-main-keypair
  (testing "Main function with --keypair flag writes both key files"
    (try
      (with-out-str
        (keygen/-main "-K" "test-main-keypair.key"))
      (is (.exists (io/file "test-main-keypair.key")))
      (is (.exists (io/file "test-main-keypair.key.pub")))
      (finally
        (io/delete-file "test-main-keypair.key" true)
        (io/delete-file "test-main-keypair.key.pub" true)))))
