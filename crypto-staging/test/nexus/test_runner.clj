(ns nexus.test-runner
  (:require  [clojure.test :as t]))

(ns nexus.test-runner)
(ns nexus.test-runner
  (:require [clojure.test :refer [run-tests]]
            [nexus.crypto-test]
            [nexus.keygen-test]))

(defn -main []
  (run-tests 'nexus.crypto-test 'nexus.keygen-test))
