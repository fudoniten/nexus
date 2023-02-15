(ns nexus.keygen
  (:require [nexus.crypto :as crypto]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:gen-class))

(def cli-opts
  [["-a" "--algorithm ALGO" "Algorithm key to generate." :default "HmacSHA512"]
   ["-s" "--seed SEED"      "Seed used to generate key."]
   ["-h" "--help"]])

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus-generate-key [opts] <FILENAME>"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- write-key [{:keys [key filename]}]
  (with-open [file (io/writer filename)]
    (.write file (crypto/encode-key key))))

(defn- gen-key [{:keys [algorithm seed]}]
  (if seed
    (crypto/generate-key algorithm seed)
    (crypto/generate-key algorithm)))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-opts)]
    (when (seq errors) (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (not (= 1 (count arguments))) (msg-quit 1 (usage summary ["missing required paramater FILENAME"])))
    (-> options
        (assoc :filename (first arguments))
        (assoc :key      (gen-key options))
        (write-key))))
