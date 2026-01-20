(ns nexus.client-test
  (:require [clojure.test :refer :all]
            [fudo-clojure.http.request :as req]
            [nexus.client :refer :all :as client]
            [nexus.crypto :refer [generate-key encode-key]]))

(deftest test-to-path-elem
  (testing "to-path-elem function"
    (is (= "keyword" (to-path-elem :keyword)))
    (is (= "string" (to-path-elem "string")))
    (is (thrown? Exception (to-path-elem 123)))))

(deftest test-build-path
  (testing "build-path function"
    (is (= "/api/v2/domain/example.com/host/test/ipv4"
           (build-path :api :v2 :domain "example.com" :host "test" :ipv4)))))

(deftest test-base-request
  (testing "base-request function"
    (let [req (base-request "localhost" 8080)]
      (is (= "localhost" (req/host req)))
      (is (= 8080 (req/port req))))))

(deftest test-send-ipv4-request
  (testing "send-ipv4-request function"
    (let [req (send-ipv4-request :hostname "test" :domain "example.com" :server "localhost" :port 8080 :ip "127.0.0.1")]
      (is (= "PUT" (req/method req)))
      (is (= "/api/v2/domain/example.com/host/test/ipv4?" (req/request-path req)))
      (is (= "127.0.0.1" (req/body req))))))

(deftest test-send-ipv6-request
  (testing "send-ipv6-request function"
    (let [req (send-ipv6-request :hostname "test" :domain "example.com" :server "localhost" :port 8080 :ip "::1")]
      (is (= "PUT" (req/method req)))
      (is (= "/api/v2/domain/example.com/host/test/ipv6?" (req/request-path req)))
      (is (= "::1" (req/body req))))))

(deftest test-send-sshfps-request
  (testing "send-sshfps-request function"
    (let [req (send-sshfps-request :hostname "test" :domain "example.com" :server "localhost" :port 8080 :sshfps "sshfp-data")]
      (is (= "PUT" (req/method req)))
      (is (= "/api/v2/domain/example.com/host/test/sshfps?" (req/request-path req)))
      (is (= "sshfp-data" (req/body req))))))

(deftest test-make-signature-generator
  (testing "make-signature-generator function"
    (let [hmac-key (encode-key (generate-key "HmacSHA512"))
          sign (make-signature-generator hmac-key)]
      (is (string? (sign "message"))))))

(deftest test-make-request-authenticator
  (testing "make-request-authenticator function"
    (let [hmac-key (encode-key (generate-key "HmacSHA512"))
          authenticator (make-request-authenticator {::client/hmac-key hmac-key ::client/hostname "test-host"})
          req (-> (base-request "localhost" 8080)
                  (req/as-get)
                  (req/with-path "/test"))
          authenticated-req (authenticator req)]
      (is (contains? (req/headers authenticated-req) "Access-Signature"))
      (is (contains? (req/headers authenticated-req) "Access-Timestamp"))
      (is (contains? (req/headers authenticated-req) "Access-Hostname")))))

(run-tests)
