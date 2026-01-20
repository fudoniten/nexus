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
        filename "test-key.txt"]
    (testing "Writing key to file"
      (try
        (keygen/write-key {:key key :filename filename})
        (is (.exists (io/file filename)))
        (finally
          (io/delete-file filename))))))

(deftest test-main
  (testing "Main function with verbose flag"
    (try
      (with-out-str
        (keygen/-main "-v" "-a" "HmacSHA512" "test-key.txt"))
      (is (.exists (io/file "test-key.txt")))
      (finally
        (io/delete-file "test-key.txt")))))
