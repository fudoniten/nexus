(ns nexus.client.cli
  (:require [nexus.client :as client]
            [fudo-clojure.ip :as ip]
            [fudo-clojure.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.core.async :refer [chan >!! <!! go-loop timeout alt!]]
            [clojure.set :as set]
            [clojure.pprint :refer [pprint]])
  (:import java.net.InetAddress
           [java.io StringWriter PrintWriter])
  (:gen-class))

(def cli-opts
  [["-4" "--ipv4" "Send IPv4 address to the DDNS server."
    :default true]
   ["-6" "--ipv6" "Send IPv6 address to the DDNS server."
    :default true]
   ["-f" "--sshfps SSHFPS_FILE" "Files containing SSHFPs for the current host."
    :default   []
    :multi     true
    :update-fn conj]
   ["-H" "--hostname HOSTNAME" "The name of this host."]
   ["-A" "--alias ALIAS" "Aliases referring to this host. In the format
   `alias:domain.com`. May be specified more than once."
    :default   []
    :multi     true
    :update-fn conj]
   ["-s" "--server SERVER" "Hostname(s) of the Nexus DDNS server."
    :multi     true
    :update-fn conj]
   ["-k" "--key-file FILE" "Location of host HMAC key file."]
   ["-p" "--port PORT" "Port on which the Nexus DDNS server is listening."
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a valid port number."]]
   ["-d" "--delay-seconds SECONDS" "Delay between reports."
    :default 360
    :parse-fn #(* 1000 (Integer/parseInt %))]
   ["-D" "--domain DOMAIN" "Domain to which this host belongs. May be specified more than once."
    :multi     true
    :update-fn conj]
   ["-c" "--certificate-authority CA" "Certificate authority trusted by the client."
    :multi     true
    :update-fn conj
    :default   []]
   ["-t" "--tailscale" "Report tailscale IPs instead of public IPs."
    :default false]
   ["-P" "--private" "Report priate IPs instead of public IPs."
    :default false]
   ["-h" "--help" "Print this mesage."]
   ["-v" "--verbose" "Verbose output."
    :default false]])

(defn capture-stack-trace
  "Captures the stack trace of an exception as a string."
  [e]
  (let [string-writer (StringWriter.)
        print-writer  (PrintWriter. string-writer)]
    (.printStackTrace e print-writer)
    (.flush print-writer)
    (.toString string-writer)))

(defn parse-opts
  "Parses command-line options and checks for missing required parameters."
  [args required cli-opts]
  (let [{:keys [options]
         :as result}     (cli/parse-opts args cli-opts)
        missing          (set/difference required (-> options keys set))
        missing-errors   (map #(format "missing required parameter: %s" %)
                              missing)]
    (update result :errors concat missing-errors)))

(defn msg-quit
  "Prints a message and exits the program with the given status."
  [status msg]
  (println msg)
  (System/exit status))

(defn usage
  "Generates a usage message with optional error messages."
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus-client [opts]"
                                  ""
                                  "Options:"
                                  summary])
                         (str/join \newline))))

(defn get-public-ipv4
  "Retrieves the first public IPv4 address of the host."
  []
  (->> (ip/get-host-ips)
       (filter ip/ipv4?)
       (filter ip/public?)
       (first)))

(defn get-public-ipv6
  "Retrieves the first public IPv6 address of the host."
  []
  (->> (ip/get-host-ips)
       (filter ip/ipv6?)
       (filter ip/public?)
       (first)))

(defn get-private-ipv4
  "Retrieves the first private IPv4 address of the host."
  []
  (->> (ip/get-host-ips)
       (filter ip/ipv4?)
       (filter ip/private?)
       (first)))

(defn get-private-ipv6
  "Retrieves the first private IPv6 address of the host."
  []
  (->> (ip/get-host-ips)
       (filter ip/ipv6?)
       (filter ip/private?)
       (first)))

(defn get-tailscale-ipv4
  "Retrieves the first Tailscale IPv4 address of the host."
  []
  (->> (ip/get-host-ips)
       (filter ip/ipv4?)
       (filter ip/tailscale?)
       (first)))

(defn get-tailscale-ipv6
  "Retrieves the first Tailscale IPv6 address of the host."
  []
    (->> (ip/get-host-ips)
         (filter ip/ipv6?)
         (filter ip/tailscale?)
         (first)))

(defn report-ipv4!
  "Reports the IPv4 address to the Nexus server."
  [logger client ip verbose]
  (log/info! logger (str "Attempting to report IPv4: " ip))
  (if ip
    (do
      (when verbose (println (str "reporting v4 ip: " ip)))
      (client/send-ipv4! client ip))
    (log/info! logger "no ipv4 address found, skipping")))

(defn report-ipv6!
  "Reports the IPv6 address to the Nexus server."
  [logger client ip verbose]
  (log/info! logger (str "Attempting to report IPv6: " ip))
  (if ip
    (do
      (when verbose (println (str "reporting v6 ip: " ip)))
      (client/send-ipv6! client ip))
    (log/info! logger "no ipv6 address found, skipping")))

(defn report-sshfps!
  "Reports SSHFP records to the Nexus server."
  [logger client sshfps verbose]
  (log/info! logger (str "Attempting to report SSHFPs: " sshfps))
  (if (seq sshfps)
    (do
      (when verbose (println (str "reporting " (count sshfps) " ssh fingerprints")))
      (client/send-sshfps! client sshfps))
    (log/info! logger "no sshfps provided, skipping")))

(defprotocol DataFetcher
  (ipv4   [_])
  (ipv6   [_])
  (sshfps [_]))

(defn public-fetcher
  "DataFetcher implementation for public IPs."
  [sshfps]
  (reify DataFetcher
    (ipv4   [_] (get-public-ipv4))
    (ipv6   [_] (get-public-ipv6))
    (sshfps [_] sshfps)))

(defn private-fetcher
  "DataFetcher implementation for private IPs."
  [sshfps]
  (reify DataFetcher
    (ipv4   [_] (get-private-ipv4))
    (ipv6   [_] (get-private-ipv6))
    (sshfps [_] sshfps)))

(defn tailscale-fetcher
  "DataFetcher implementation for Tailscale IPs."
  [sshfps]
  (reify DataFetcher
    (ipv4   [_] (get-tailscale-ipv4))
    (ipv6   [_] (get-tailscale-ipv6))
    (sshfps [_] sshfps)))

(defn execute!
  "Executes the reporting process, periodically sending IP and SSHFP data to the server."
  [& {:keys [timeout-ms logger client verbose fetcher]}]
  (log/info! logger "Starting execution loop for reporting.")
  (report-sshfps! logger client (sshfps fetcher) verbose)
  (let [stop-chan (chan)]
    (go-loop [continue true]
      (if continue
        (do (try
              (report-ipv4! logger client (ipv4 fetcher) verbose)
              (report-ipv6! logger client (ipv6 fetcher) verbose)
              (catch Exception e
                (println (capture-stack-trace e))))
            (recur (alt! (timeout timeout-ms) true
                         stop-chan            false)))
        nil))))

(defn -main
  "Main entry point for the Nexus client CLI."
  [& args]
  (log/info! (log/print-logger) (format "Starting Nexus client with arguments: %s" args))
  (let [{:keys [options _ errors summary]} (parse-opts args #{:server :key-file} cli-opts)]
    (when (seq errors)    (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (empty? (:server options))
      (msg-quit 1 (usage summary ["At least one server must be specified."])))
    (let [hostname       (or (:hostname options)
                             (-> (InetAddress/getLocalHost) (.getHostName)))
          domain-aliases (into {}
                               (comp (map (fn [[domain pairs]] [domain (map first pairs)])))
                               (->> (:alias options)
                                    (map #(str/split % #":"))
                                    (group-by second)))
          client         (client/combine-nexus-clients
                          (map (fn [domain]
                                 (when (:verbose options)
                                   (println (str "initializing domain: " domain)))
                                 (client/connect :verbose  (:verbose options)
                                                 :domain   domain
                                                 :hostname hostname
                                                 :aliases  (get domain-aliases domain [])
                                                 :servers  (:server options)
                                                 :port     (:port options)
                                                 :hmac-key (-> options :key-file slurp)
                                                 :logger   (log/print-logger)
                                                 :ca-map   (into {}
                                                                 (map-indexed
                                                                  (fn [i cert]
                                                                    [(keyword (str "ca" i))
                                                                     cert])
                                                                  (:certificate-authority options)))))
                               (:domain options)))
          logger         (log/print-logger)
          sshfps         (some->> (:sshfps options)
                                  (map slurp)
                                  (mapcat str/split-lines))
          data-fetcher   (cond (:tailscale options) (tailscale-fetcher sshfps)
                               (:public options)    (private-fetcher   sshfps)
                               :else                (public-fetcher    sshfps))
          stop-chan      (execute! :timeout-ms (:delay-seconds options)
                                   :logger     logger
                                   :client     client
                                   :fetcher    data-fetcher
                                   :verbose    (:verbose options))
          catch-shutdown (chan)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (>!! stop-chan true)
      (System/exit 0))))
