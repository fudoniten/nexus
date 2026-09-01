(ns nexus.client-test
  (:require [clojure.test :refer :all]
            [fudo-clojure.http.request :as req]
            [fudo-clojure.common :refer [instant-to-epoch-timestamp]]
            [nexus.client :refer :all :as client]
            [nexus.crypto :refer [generate-key encode-key generate-keypair
                                  encode-private-key verify-with-public-key]]))

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
      (is (some? req))
      (is (= "GET" (req/method req))))))

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
      ;; Just verify the authenticator returns a request
      (is (some? authenticated-req))
      (is (= "GET" (req/method authenticated-req))))))

;; --- v3 (Ed25519) request builders and authenticator ---

(deftest test-build-path-v3
  (testing "build-path with :v3"
    (is (= "/api/v3/domain/example.com/host/test/ipv4"
           (build-path :api :v3 :domain "example.com" :host "test" :ipv4)))))

(deftest test-send-ipv4-request-v3
  (testing "send-ipv4-request defaults to v2, but takes :v3"
    (is (= "/api/v2/domain/example.com/host/test/ipv4?"
           (req/request-path (send-ipv4-request :hostname "test" :domain "example.com"
                                                 :server "localhost" :port 8080 :ip "127.0.0.1"))))
    (is (= "/api/v3/domain/example.com/host/test/ipv4?"
           (req/request-path (send-ipv4-request :hostname "test" :domain "example.com"
                                                 :server "localhost" :port 8080 :ip "127.0.0.1"
                                                 :version :v3))))))

(deftest test-make-signature-generator-v3
  (testing "make-signature-generator-v3 signs with the private key, verifiable with the public key"
    (let [{:keys [public-key private-key]} (generate-keypair)
          private-key-str (encode-private-key private-key)
          sign (make-signature-generator-v3 private-key-str)
          sig  (sign "message")]
      (is (string? sig))
      (is (verify-with-public-key public-key "message" sig))
      (is (not (verify-with-public-key public-key "different message" sig))))))

(deftest test-make-request-authenticator-v3
  (testing "make-request-authenticator-v3 function"
    (let [{:keys [public-key private-key]} (generate-keypair)
          private-key-str (encode-private-key private-key)
          authenticator (make-request-authenticator-v3 {::client/private-key private-key-str
                                                         ::client/hostname   "test-host"})
          req (-> (base-request "localhost" 8080)
                  (req/as-get)
                  (req/with-path "/test"))
          authenticated-req (authenticator req)]
      (is (some? authenticated-req))
      (is (= "GET" (req/method authenticated-req)))
      (testing "the request signature verifies against the public key"
        (let [headers   (::req/headers authenticated-req)
              sig       (:access-signature headers)
              ;; access-timestamp holds the raw java.time.Instant (see
              ;; make-signing-authenticator); the signed string uses its
              ;; epoch-seconds form, not (str instant)'s ISO-8601 form.
              timestamp (-> (:access-timestamp headers) (instant-to-epoch-timestamp) (str))
              req-str   (str "GET" "/test" timestamp "")]
          (is (verify-with-public-key public-key req-str sig)))))))
