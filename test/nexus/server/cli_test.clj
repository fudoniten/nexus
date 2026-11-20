(ns nexus.server.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.tools.cli]
            [nexus.server.cli :as cli]))

(defn- temp-file
  "Creates a temporary file that exists on disk, for the file-existence checks
  validate-config makes."
  []
  (doto (java.io.File/createTempFile "nexus-cli-test" ".json")
    (.deleteOnExit)))

(defn- base-config
  "A config whose every file option names a file that exists."
  []
  {:host-alias-map        (.getPath (temp-file))
   :host-public-keys      (.getPath (temp-file))
   :challenge-public-keys (.getPath (temp-file))
   :pdns-api-key-file     (.getPath (temp-file))})

(deftest existing-files-pass
  (is (nil? (:errors (cli/validate-config (base-config))))))

(deftest named-files-must-exist
  (let [missing (.getPath (io/file (System/getProperty "java.io.tmpdir")
                                   "nexus-cli-test-does-not-exist.json"))]
    (doseq [k [:host-public-keys :challenge-public-keys :host-alias-map :pdns-api-key-file]]
      (testing (str "a missing " (name k) " file is an error naming it")
        (is (some #(re-find (re-pattern (str "^" (name k) " file does not exist")) %)
                  (:errors (cli/validate-config (assoc (base-config) k missing)))))))))

(deftest outage-allowance-covers-server-downtime
  (let [now (java.time.Instant/parse "2026-11-20T00:00:00Z")]
    (testing "no records of the server's yet: no allowance"
      (is (= java.time.Duration/ZERO (cli/outage-allowance nil now))))
    (testing "the time since the server last refreshed a record"
      (is (= (java.time.Duration/ofDays 90)
             (cli/outage-allowance (.minus now (java.time.Duration/ofDays 90)) now))))
    (testing "a refresh stamped after now (clock skew) is no allowance"
      (is (= java.time.Duration/ZERO
             (cli/outage-allowance (.plusSeconds now 5) now))))))

(deftest record-lifespan-option
  (testing "defaults to 60 days"
    (is (= 60 (-> (clojure.tools.cli/parse-opts [] cli/cli-opts) :options :record-lifespan))))
  (testing "a negative lifespan is refused"
    (is (seq (:errors (clojure.tools.cli/parse-opts ["--record-lifespan=-1"] cli/cli-opts))))))
