(ns nexus.integration-test
  "Integration tests for Nexus DDNS client/server communication.
   
   These tests use a real PostgreSQL database to verify end-to-end
   functionality including schema initialization, HTTP request handling,
   HMAC authentication, and database persistence."
  (:require [clojure.test :refer [deftest testing is use-fixtures run-tests]]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.string]
            [nexus.server :as server]
            [nexus.crypto :as crypto]
            [nexus.sql-datastore :as store]
            [nexus.host-alias-map :as host-map]
            [nexus.authenticator :as auth]
            [ring.mock.request :as mock]
            [next.jdbc :as jdbc]
            [eftest.runner :as eftest]))

;; Test configuration
(def ^:dynamic *db-config* nil)
(def ^:dynamic *app* nil)
(def ^:dynamic *test-keys* nil)
(def ^:dynamic *datastore* nil)

(defn init-database!
  "Initialize test database schema from SQL files."
  [db-spec]
  (let [schema-sql (slurp "sql/powerdns-schema.sql")
        trigger-sql (slurp "sql/update-serial-trigger.sql")
        challenges-sql "CREATE TABLE IF NOT EXISTS challenges (
          domain_id INTEGER NOT NULL,
          challenge_id UUID NOT NULL,
          hostname VARCHAR(255) NOT NULL,
          created_at TIMESTAMP NOT NULL DEFAULT NOW(),
          record_id BIGINT NOT NULL,
          active BOOLEAN NOT NULL DEFAULT TRUE,
          PRIMARY KEY(domain_id, challenge_id)
        );"]
    (jdbc/execute! db-spec [schema-sql])
    (jdbc/execute! db-spec [trigger-sql])
    (jdbc/execute! db-spec [challenges-sql])))

(defn create-test-domain!
  "Create a test domain with SOA and NS records."
  [db-spec domain-name]
  (let [timestamp (System/currentTimeMillis)]
    ;; Insert domain
    (jdbc/execute! db-spec
                   ["INSERT INTO domains (name, type, notified_serial) 
                     VALUES (?, 'MASTER', ?)
                     ON CONFLICT DO NOTHING"
                    domain-name timestamp])
    
    ;; Get domain ID
    (let [domain-id (:domains/id (first (jdbc/execute! db-spec
                                                       ["SELECT id FROM domains WHERE name = ?"
                                                        domain-name])))]
      ;; Insert SOA record
      (jdbc/execute! db-spec
                     ["INSERT INTO records (domain_id, name, type, content, ttl)
                       VALUES (?, ?, 'SOA', ?, 3600)
                       ON CONFLICT DO NOTHING"
                      domain-id domain-name
                      (str "ns1.example.com. admin.example.com. "
                           timestamp " 10800 3600 1209600 3600")])
      
      ;; Insert NS record
      (jdbc/execute! db-spec
                     ["INSERT INTO records (domain_id, name, type, content, ttl)
                       VALUES (?, ?, 'NS', 'ns1.example.com.', 3600)
                       ON CONFLICT DO NOTHING"
                      domain-id domain-name])
      domain-id)))

(defn make-authenticated-request
  "Create an authenticated request with HMAC signature."
  [method path hostname key-str & {:keys [body]}]
  (let [timestamp (quot (System/currentTimeMillis) 1000)  ; Convert to seconds
        timestamp-str (str timestamp)
        req-str (str (clojure.string/upper-case (name method)) path timestamp-str (or body ""))
        key (crypto/decode-key key-str)
        sig (crypto/generate-signature key req-str)
        req (-> (mock/request method path)
                (mock/header "access-signature" sig)
                (mock/header "access-timestamp" timestamp-str)
                (mock/header "access-hostname" hostname))]
    (if body
      (mock/body req body)
      req)))

(defn setup-test-environment!
  "Set up test database and server app."
  []
  (let [;; Use PostgreSQL from environment or localhost
        db-host (or (System/getenv "POSTGRES_HOST") "localhost")
        db-port (or (System/getenv "POSTGRES_PORT") "5432")
        db-name (or (System/getenv "POSTGRES_DB") "nexus_test")
        db-user (or (System/getenv "POSTGRES_USER") "postgres")
        db-pass (or (System/getenv "POSTGRES_PASSWORD") "")
        
        db-spec {:dbtype "postgresql"
                 :dbname db-name
                 :host db-host
                 :port (Integer/parseInt db-port)
                 :user db-user
                 :password db-pass}
        
        ;; Generate test keys
        host-key (crypto/generate-key "HmacSHA256")
        host-key-str (crypto/encode-key host-key)
        test-keys {:testhost host-key-str}
        
        ;; Create datastore
        datastore (store/->SqlDataStore false db-spec)
        
        ;; Create authenticators and host mapper
        host-authenticator (auth/make-authenticator test-keys false)
        challenge-authenticator (auth/make-authenticator {} false)
        host-mapper (host-map/->HostAliasMap {})
        
        ;; Create server app
        app (server/create-app :host-authenticator host-authenticator
                              :challenge-authenticator challenge-authenticator
                              :data-store datastore
                              :host-mapper host-mapper
                              :verbose false)]
    
    ;; Initialize database schema
    (init-database! db-spec)
    
    ;; Create test domain
    (create-test-domain! db-spec "test.example.com")
    
    (alter-var-root #'*db-config* (constantly db-spec))
    (alter-var-root #'*app* (constantly app))
    (alter-var-root #'*test-keys* (constantly test-keys))
    (alter-var-root #'*datastore* (constantly datastore))))

(defn teardown-test-environment!
  "Clean up test database."
  []
  (when *db-config*
    ;; Clean up database
    (try
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS challenges CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS cryptokeys CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS domainmetadata CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS comments CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS records CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS domains CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS supermasters CASCADE"])
      (jdbc/execute! *db-config* ["DROP TABLE IF EXISTS tsigkeys CASCADE"])
      (catch Exception e
        (println "Warning: Failed to clean up database:" (.getMessage e))))))

(use-fixtures :once
  (fn [f]
    (try
      (setup-test-environment!)
      (f)
      (finally
        (teardown-test-environment!)))))

;; Integration Tests

(deftest test-server-health-check
  (testing "Server health endpoint responds without authentication"
    (let [response (*app* (mock/request :get "/api/v2/health"))]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))

(deftest test-ipv4-update-and-retrieval
  (testing "Can update IPv4 address and retrieve it from database"
    (let [domain "test.example.com"
          hostname "testhost"
          ipv4 "192.0.2.100"
          key-str (get *test-keys* (keyword hostname))
          path (str "/api/v2/domain/" domain "/host/" hostname "/ipv4")]
      
      ;; Update IPv4
      (let [req (make-authenticated-request :put path hostname key-str :body ipv4)
            response (*app* req)]
        (is (= 200 (:status response))
            (str "PUT request failed: " (:status response) " - " (:body response))))
      
      ;; Verify in database
      (let [results (jdbc/execute! *db-config*
                                   ["SELECT r.content FROM records r
                                     JOIN domains d ON r.domain_id = d.id
                                     WHERE d.name = ? AND r.name = ? AND r.type = 'A'"
                                    domain (str hostname "." domain)])
            record-content (:records/content (first results))]
        (is (= ipv4 record-content)
            "Database should contain the updated IP address"))
      
      ;; Retrieve via API
      (let [req (make-authenticated-request :get path hostname key-str)
            response (*app* req)]
        (is (= 200 (:status response)))
        (is (= ipv4 (:body response)))))))

(deftest test-ipv6-update-and-retrieval
  (testing "Can update IPv6 address and retrieve it from database"
    (let [domain "test.example.com"
          hostname "testhost"
          ipv6 "2001:db8::1"
          key-str (get *test-keys* (keyword hostname))
          path (str "/api/v2/domain/" domain "/host/" hostname "/ipv6")]
      
      ;; Update IPv6
      (let [req (make-authenticated-request :put path hostname key-str :body ipv6)
            response (*app* req)]
        (is (= 200 (:status response))))
      
      ;; Verify in database
      (let [results (jdbc/execute! *db-config*
                                   ["SELECT r.content FROM records r
                                     JOIN domains d ON r.domain_id = d.id
                                     WHERE d.name = ? AND r.name = ? AND r.type = 'AAAA'"
                                    domain (str hostname "." domain)])
            record-content (:records/content (first results))]
        (is (= ipv6 record-content)))
      
      ;; Retrieve via API
      (let [req (make-authenticated-request :get path hostname key-str)
            response (*app* req)]
        (is (= 200 (:status response)))
        (is (= ipv6 (:body response)))))))

(deftest test-sshfp-update-and-retrieval
  (testing "Can update SSH fingerprints and retrieve them"
    (let [domain "test.example.com"
          hostname "testhost"
          ;; SSHFP format: "algorithm type fingerprint"
          sshfps "1 1 0123456789abcdef\n3 2 fedcba9876543210"
          key-str (get *test-keys* (keyword hostname))
          path (str "/api/v2/domain/" domain "/host/" hostname "/sshfps")]
      
      ;; Update SSHFPs
      (let [req (make-authenticated-request :put path hostname key-str :body sshfps)
            response (*app* req)]
        (is (= 200 (:status response))))
      
      ;; Verify in database
      (let [results (jdbc/execute! *db-config*
                                   ["SELECT COUNT(*) as count FROM records r
                                     JOIN domains d ON r.domain_id = d.id
                                     WHERE d.name = ? AND r.name = ? AND r.type = 'SSHFP'"
                                    domain (str hostname "." domain)])
            count (:count (first results))]
        (is (= 2 count) "Should have 2 SSHFP records"))
      
      ;; Retrieve via API
      (let [req (make-authenticated-request :get path hostname key-str)
            response (*app* req)]
        (is (= 200 (:status response)))
        (is (.contains (:body response) "0123456789abcdef"))
        (is (.contains (:body response) "fedcba9876543210"))))))

(deftest test-batch-update
  (testing "Can update multiple record types in a single batch request"
    (let [domain "test.example.com"
          hostname "testhost"
          batch-data (json/write-str {:ipv4 "192.0.2.200"
                                       :ipv6 "2001:db8::2"
                                       :sshfps "1 1 batch123456789"})
          key-str (get *test-keys* (keyword hostname))
          path (str "/api/v2/domain/" domain "/host/" hostname "/batch")]
      
      ;; Batch update
      (let [req (-> (make-authenticated-request :put path hostname key-str :body batch-data)
                    (mock/content-type "application/json"))
            response (*app* req)]
        (is (= 200 (:status response))))
      
      ;; Verify IPv4 in database
      (let [results (jdbc/execute! *db-config*
                                   ["SELECT r.content FROM records r
                                     JOIN domains d ON r.domain_id = d.id
                                     WHERE d.name = ? AND r.name = ? AND r.type = 'A'"
                                    domain (str hostname "." domain)])
            record-content (:records/content (first results))]
        (is (= "192.0.2.200" record-content)))
      
      ;; Verify IPv6 in database
      (let [results (jdbc/execute! *db-config*
                                   ["SELECT r.content FROM records r
                                     JOIN domains d ON r.domain_id = d.id
                                     WHERE d.name = ? AND r.name = ? AND r.type = 'AAAA'"
                                    domain (str hostname "." domain)])
            record-content (:records/content (first results))]
        (is (= "2001:db8::2" record-content))))))

(deftest test-authentication-failure
  (testing "Server rejects requests with invalid HMAC signature"
    (let [domain "test.example.com"
          hostname "testhost"
          ipv4 "192.0.2.250"
          wrong-key (crypto/encode-key (crypto/generate-key "HmacSHA256"))
          path (str "/api/v2/domain/" domain "/host/" hostname "/ipv4")]
      
      (let [req (make-authenticated-request :put path hostname wrong-key :body ipv4)
            response (*app* req)]
        (is (or (= 401 (:status response))
                (= 403 (:status response)))
            "Should return 401 or 403 for invalid signature")))))

(deftest test-missing-authentication
  (testing "Server rejects unauthenticated requests"
    (let [domain "test.example.com"
          hostname "testhost"
          path (str "/api/v2/domain/" domain "/host/" hostname "/ipv4")]
      
      (let [response (*app* (mock/request :get path))]
        (is (or (= 401 (:status response))
                (= 403 (:status response)))
            "Should reject requests without authentication headers")))))

(deftest test-soa-serial-auto-increment
  (testing "SOA serial auto-increments when records change"
    (let [domain "test.example.com"
          hostname "testhost"
          key-str (get *test-keys* (keyword hostname))
          path (str "/api/v2/domain/" domain "/host/" hostname "/ipv4")]
      
      ;; Get initial serial
      (let [initial-serial (-> (jdbc/execute! *db-config*
                                              ["SELECT notified_serial FROM domains WHERE name = ?"
                                               domain])
                               first
                               :domains/notified_serial)]
        
        ;; Update a record
        (let [req (make-authenticated-request :put path hostname key-str :body "192.0.2.123")
              response (*app* req)]
          (is (= 200 (:status response))))
        
        ;; Check serial incremented
        (let [new-serial (-> (jdbc/execute! *db-config*
                                            ["SELECT notified_serial FROM domains WHERE name = ?"
                                             domain])
                             first
                             :domains/notified_serial)]
          (is (> new-serial initial-serial)
              "SOA serial should increment after record update"))))))

(defn -main
  "Run integration tests"
  [& args]
  (let [all-tests (eftest/find-tests "test")
        integration-tests (filter (fn [test-var]
                                     (clojure.string/includes?
                                      (str (-> test-var meta :ns ns-name))
                                      "integration"))
                                   all-tests)
        summary (eftest/run-tests integration-tests)]
    (System/exit (if (zero? (+ (:fail summary) (:error summary)))
                   0
                   1))))
