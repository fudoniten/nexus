(ns nexus.server.cli
  (:require [clojure.java.io :as io]
            [nexus.server :as server]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [nexus.pdns-datastore :as pdns-store]
            [nexus.datastore :as store]
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
  [[nil "--host-public-keys HOST_PUBLIC_KEYS"
    "File containing host/Ed25519-public-key pairs, in json format. Not secret."]

   [nil "--challenge-public-keys CHALLENGE_PUBLIC_KEYS"
    "File containing challenge-client Ed25519 public keys, in json format. Not secret."]

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

   [nil "--pdns-api-url URL"
    "Base URL of the PowerDNS HTTP API, e.g. http://127.0.0.1:8081. Every record change goes through it."]

   [nil "--pdns-api-key-file FILE"
    "File containing the PowerDNS API key."]

   [nil "--record-ttl SECONDS"
    "TTL for host records written by Nexus."
    :parse-fn #(Integer/parseInt %)
    :default 3600]

   [nil "--challenge-max-age SECONDS"
    "ACME challenge records older than this are removed."
    :parse-fn #(Integer/parseInt %)
    :default 86400]

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
  "Check that every file option names a file that exists."
  [options]
  (let [file-options [:host-public-keys :challenge-public-keys
                      :host-alias-map :pdns-api-key-file]
        errors (for [k file-options
                     :let [f (get options k)]
                     :when (and f (not (file-exists? f)))]
                 (format "%s file does not exist: %s" (name k) f))]
    (if (seq errors)
      (assoc options :errors (vec errors))
      options)))

(defn initialize-app
  "Initialize the application components"
  [{:keys [host-alias-map host-public-keys challenge-public-keys verbose] :as config}]
  (let [store (pdns-store/connect config)]
    {:store store
     :app
     (server/create-app :host-authenticator      (auth/initialize-pubkey-collection host-public-keys verbose)
                        :challenge-authenticator (auth/initialize-pubkey-collection challenge-public-keys verbose)
                        :data-store              store
                        :host-mapper             (host-mapper/make-mapper host-alias-map)
                        :verbose                 verbose)}))

(defn start-challenge-expiry!
  "Remove stale ACME challenge records every hour. They are normally deleted
  by the client that made them; this catches the ones a failed or
  interrupted run left behind."
  [store max-age-seconds]
  (doto (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)
    (.scheduleWithFixedDelay
     (fn []
       (try
         (let [n (store/expire-challenge-records store max-age-seconds)]
           (when (pos? n)
             (println (format "expired %d stale challenge record(s)" n))))
         (catch Throwable e
           (println (format "challenge expiry failed: %s" (.getMessage e))))))
     1 60 java.util.concurrent.TimeUnit/MINUTES)))

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
  (let [required-keys #{:host-public-keys
                        :challenge-public-keys
                        :database
                        :database-user
                        :database-password-file
                        :database-host
                        :database-port
                        :listen-host
                        :listen-port
                        :pdns-api-url
                        :pdns-api-key-file
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
            {:keys [app store]} (initialize-app (assoc config :metrics-registry metrics-registry))]
        (start-challenge-expiry! store (:challenge-max-age config))
        (start-server! app config)))))
