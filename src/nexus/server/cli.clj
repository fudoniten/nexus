(ns nexus.server.cli
  (:require [clojure.java.io :as io]
            [nexus.server :as server]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [nexus.sql-datastore :as sql-store]
            [nexus.authenticator :as auth]
            [nexus.host-alias-map :as host-mapper]
            [nexus.metrics :as metrics]
            [ring.adapter.jetty :refer [run-jetty]]
            [clojure.core.async :refer [chan >!! <!!]]
            [clojure.set :as set])
  (:gen-class))

(def VERSION "0.1.1"
  "The current version of the Nexus server.")

(def cli-opts
  "Definition of the command-line options for the Nexus server."
  [["-k" "--host-keys HOST_KEYS"
    "File containing host/key pairs, in json format."]

   ["-c" "--challenge-keys CHALLENGE_KEYS"
    "File containing challenge keys, in json format."]

   ["-M" "--host-alias-map HOST_ALIAS_MAP"
    "File containing host to domain/alias mapping, in json format."]

   ["-D" "--database DATABASE"
    "Database name of the PowerDNS database."]

   ["-U" "--database-user DB_USER"
    "User as which to connect to the PowerDNS database."]

   ["-W" "--database-password-file DB_PASSWORD_FILE"
    "File containing password with which to connect to the PowerDNS database."]

   ["-H" "--database-host DB_HOSTNAME"
    "Hostname of the Postgresql PowerDNS database."]

   ["-P" "--database-port DB_PORT"
    "Port of the Postgresql server backing PowerDNS."
    :parse-fn #(Integer/parseInt %)
    :default 5432]

   ["-h" "--listen-host HOST"
    "Hostname or IP address on which to listen."]

   ["-p" "--listen-port PORT"
    "Port on which to listen for incoming requests."
    :parse-fn #(Integer/parseInt %)]

   ["-v" "--verbose" "Verbose output."
    :default false]

   ["-V" "--version" "Print the current version."]])

(defn- usage
  "Generate the usage message for the Nexus server CLI.
  Takes the options summary and optional errors, and returns a formatted string."
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn- msg-quit
  "Print a message and exit with the specified status code."
  [status msg]
  (println msg)
  (System/exit status))

(defn file-exists? [filename]
  (.exists (io/file filename)))

(defn- parse-opts
  "Parse the command-line arguments using the specified options.
  Checks for missing required options and returns the parsed result."
  [args required cli-opts]
  (let [{:keys [options]
         :as result}     (cli/parse-opts args cli-opts)
        missing          (set/difference required (-> options keys set))
        missing-errors   (map #(format "missing required parameter: %s" %)
                              missing)]
    (update result :errors concat missing-errors)))

(defn serve!
  "Start the server with the given app and host/port configuration."
  [app {:keys [host port]}]
  (run-jetty app {:host host :port port :join? false}))

(defn validate-config
  "Validate the configuration options"
  [options]
  (let [errors (cond-> []
                 (not (file-exists? (:host-keys options)))
                 (conj "host-keys file does not exist")

                 (not (file-exists? (:challenge-keys options)))
                 (conj "challenge-keys file does not exist")

                 (and (:host-alias-map options)
                      (not (file-exists? (:host-alias-map options))))
                 (conj "host-alias-map file does not exist"))]
    (if (seq errors)
      (assoc options :errors errors)
      options)))

(defn initialize-app
  "Initialize the application components"
  [{:keys [host-keys challenge-keys host-alias-map verbose] :as config}]
  (let [host-authenticator      (auth/initialize-key-collection host-keys verbose)
        challenge-authenticator (auth/initialize-key-collection challenge-keys verbose)
        store                   (sql-store/connect config)
        host-mapper             (host-mapper/make-mapper host-alias-map)]
    (server/create-app :host-authenticator      host-authenticator
                       :challenge-authenticator challenge-authenticator
                       :data-store              store
                       :host-mapper             host-mapper
                       :verbose                 verbose)))

(defn start-server!
  "Start the server and wait for shutdown"
  [app {:keys [listen-port listen-host verbose]}]
  (let [catch-shutdown (chan)
        server         (serve! app {:port listen-port
                                    :host listen-host})]
    (when verbose (println (format "starting nexus-server v%s" VERSION)))

    ;; Set up shutdown hook
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn [] (>!! catch-shutdown true))))

    ;; Wait for shutdown signal
    (<!! catch-shutdown)

    ;; Stop the server and exit
    (.stop server)
    (System/exit 0)))

(defn -main
  "The entry point for the Nexus server application.
  Parses command-line arguments, initializes components, and starts the server."
  [& args]
  (let [required-keys #{:host-keys
                        :challenge-keys
                        :database
                        :database-user
                        :database-password-file
                        :database-host
                        :database-port
                        :listen-host
                        :listen-port
                        :verbose}
        {:keys [options _ errors summary]}
        (parse-opts args required-keys cli-opts)]
    (when (:help options)    (msg-quit 0 (usage summary)))
    (when (:version options) (msg-quit 0 (format "nexus-server v%s" VERSION)))
    (when (seq errors)       (msg-quit 1 (usage summary errors)))
    (when (:verbose options)
      (println "Options:")
      (println (str/join \newline (map (fn [[k v]] (str "  " (name k) ": " v)) options))))
    
    (let [config (validate-config options)]
      (when-let [errors (:errors config)]
        (msg-quit 1 (usage summary errors)))
      
      (let [metrics-registry (metrics/initialize-metrics)
            app (initialize-app (assoc config :metrics-registry metrics-registry))]
        (start-server! app config)))))
