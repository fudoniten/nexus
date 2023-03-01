(ns nexus.client.cli
  (:require [nexus.client :as client]
            [fudo-clojure.ip :as ip]
            [fudo-clojure.logging :as log]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.core.async :refer [chan >!! <!! go-loop timeout alt!]]
            [fudo-clojure.http.client :as http]
            [fudo-clojure.result :as result])
  (:import java.net.InetAddress)
  (:gen-class))

(def cli-opts
  [["-4" "--ipv4" "Send IPv4 address to the DDNS server."
    :default true]
   ["-6" "--ipv6" "Send IPv6 address to the DDNS server."
    :default true]
   ["-f" "--sshfp" "SSH key fingerprint to send. Repeat to send more than one."
    :multiple  true
    :default   []
    :update-fn conj]
   ["-H" "--hostname HOSTNAME" "The name of this host."]
   ["-s" "--server SERVER" "Hostname of the Nexus DDNS server."]
   ["-k" "--key-file FILE" "Location of host HMAC key file."]
   ["-p" "--port PORT" "Port on which the Nexus DDNS server is listening."
    :default 80
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a valid port number."]]
   ["-d" "--delay-seconds SECONDS" "Delay between reports."
    :default 360
    :parse-fn #(* 1000 (Integer/parseInt %))]
   ["-D" "--domain DOMAIN" "Domain to which this host belongs. May be specified more than once."
    :default []
    :update-fn conj]
   ["-h" "--help" "Print this mesage."]])

(defn- parse-opts [args required cli-opts]
  (let [{:keys [options]
         :as result}     (cli/parse-opts args cli-opts)
        label-missing    (comp (filter (partial contains? options))
                               (map #(format "missing required parameter: %s" %)))
        missing-errors   (into [] label-missing required)]
    (update result :errors concat missing-errors)))

(defn- msg-quit [status msg]
  (println msg)
  (System/exit status))

(defn- usage
  ([summary] (usage summary []))
  ([summary errors] (->> (concat errors
                                 ["usage: nexus-client [opts]"
                                  ""
                                  "Options:"]
                                 summary)
                         (str/join \newline))))

(defn- handle-response [logger log-tag resp]
  (when (result/success? resp)
    (log/info! logger (format "successfully reported %s" log-tag))
    (log/error! logger (format "failed to report %s: %s"
                               log-tag
                               (http/status-message resp)))))

(defn- get-public-ipv4 []
  (->> (ip/get-host-ips)
       (filter ip/ipv4?)
       (filter ip/public?)
       (first)))

(defn- get-public-ipv6 []
  (->> (ip/get-host-ips)
       (filter ip/ipv6?)
       (filter ip/public?)
       (first)))

(defn- report-ipv4! [logger client]
  (let [ip (get-public-ipv4)]
    (if ip
      (handle-response logger "ipv4 address" (client/send-ipv4! client ip))
      (log/info! logger "no public ipv4 address found, skipping"))))

(defn- report-ipv6! [logger client]
  (let [ip (get-public-ipv6)]
    (if ip
      (handle-response logger "ipv6 address" (client/send-ipv6! client ip))
      (log/info! logger "no public ipv6 address found, skipping"))))

(defn- report-sshfps! [logger client sshfps]
  (if (seq sshfps)
    (handle-response logger "ssh fingerprints" (client/send-sshfps! client sshfps))
    (log/info! logger "no sshfps provided, skipping")))

(defn- report-ips! [logger client]
  (report-ipv4! logger client)
  (report-ipv6! logger client))

(defn- execute! [timeout-ms logger client sshfps]
  (report-sshfps! logger client sshfps)
  (let [stop-chan (chan)]
    (go-loop [continue true]
      (if continue
        (do (report-ips! logger client)
            (recur (alt! (timeout timeout-ms) true
                         stop-chan            false)))
        nil))))

(defn -main [& args]
  (let [{:keys [options _ errors summary]} (parse-opts args [:server :key-file] cli-opts)]
    (when (seq errors)    (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (let [hostname       (or (:hostname options)
                             (-> (InetAddress/getLocalHost) (.getHostName)))
          client         (client/combine-nexus-clients
                          (map (fn [domain]
                                 (client/connect :domain   domain
                                                 :hostname hostname
                                                 :server   (:server options)
                                                 :port     (:port options)
                                                 :hmac-key (-> options :key-file slurp)))
                               (:domain options)))
          logger         (log/print-logger)
          stop-chan      (execute! (:delay-seconds options)
                                   logger
                                   client
                                   (:sshfp options))
          catch-shutdown (chan)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (>!! catch-shutdown true))))
      (<!! catch-shutdown)
      (>!! stop-chan true)
      (System/exit 0))))
