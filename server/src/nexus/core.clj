(ns nexus.core
  (:require [nexus.server :as server]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [nexus.sql-datastore :as sql-store]
            [nexus.authenticator :as auth]
            [ring.adaptor.jetty :refer [run-jetty]])
  (:gen-class))

(def cli-opts
  [["-k" "--host-keys HOST_KEYS"
    "File containing host/key pairs, in json format."]

   ["-D" "--database DATABASE"
    "Database name of the PowerDNS database."]

   ["-U" "--database-user USER"
    "User as which to connect to the PowerDNS database."]

   ["-W" "--database-password-file PASSWORD_FILE"
    "File containing password with which to connect to the PowerDNS database."]

   ["-H" "--database-host HOSTNAME"
    "Hostname of the Postgresql PowerDNS database."]

   ["-P" "--database-port DB_PORT"
    "Port of the Postgresql server backing PowerDNS."
    :default 5432]

   ["-h" "--listen-host HOST"
    "Hostname or IP address on which to listen."]

   ["-p" "--listen-port PORT"
    "Port on which to listen for incoming requests."]

   ["-d" "--domain DOMAIN"
    "Domain for which this server acts as DDNS Nexus."]])

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus [opts]"
                                  ""
                                  "Options:"]
                                 summary)
                         (str/join \newline))))

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options]
         :as result}     (cli/parse-opts args cli-opts)
        label-missing    (comp (filter (partial contains? options))
                               (map #(format "missing required parameter: %s" %)))
        missing-errors   (into [] label-missing required)]
    (update result :errors concat missing-errors)))

(defn serve [app port]
  (run-jetty app { :port port }))

(defn -main [& args]
  (let [required-keys [:host-keys
                       :database
                       :database-user
                       :database-password-file
                       :database-host
                       :listen-host
                       :listen-port
                       :domain]
        {:keys [options _ errors summary]}
        (parse-opts args required-keys cli-opts)]
    (when (seq errors)    (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (let [authenticator (auth/read-key-collection (:host-keys options))
          store         (sql-store/connect options)
          app           (server/create-app :authenticator authenticator :data-store store)]
      (serve app (:port options)))))
