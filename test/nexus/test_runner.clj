(ns nexus.test-runner
  "Entry point for `clojure -M:test`.

  Exists for two reasons, both of which CI got wrong before it did:

  1. eftest's run-tests only *returns* a summary. Run from an inline -e
     expression, a red suite still exited 0, so the CI step reported success
     while tests were failing. This exits non-zero when anything failed.

  2. Namespaces marked ^:integration need a live PostgreSQL instance, which
     `clojure -M:test` does not provide -- they errored in their :once fixture
     on every run. They are skipped here and run deliberately, against a real
     database, with `clojure -M:integration`."
  (:require [eftest.runner :as eftest]))

(defn- integration?
  "True for a test in a namespace marked ^:integration. Accepts either a test
  var or a namespace, since that is what eftest's find-tests may hand back."
  [test]
  (let [m (meta test)]
    (boolean (or (:integration m)
                 (some-> m :ns meta :integration)))))

(defn -main [& _args]
  (let [tests    (remove integration? (eftest/find-tests "test"))
        summary  (eftest/run-tests tests {:multithread? false})
        failures (+ (:fail summary 0) (:error summary 0))]
    (when (pos? failures)
      (println (format "%d test failure(s)/error(s)" failures)))
    (System/exit (if (pos? failures) 1 0))))
