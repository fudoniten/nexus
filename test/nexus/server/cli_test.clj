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
