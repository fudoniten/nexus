#!/usr/bin/env bb

(ns nexus-client
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cheshire.core :as json])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.net InetAddress NetworkInterface]
           [java.time Instant]))

;; --- IP Detection ---

(defn get-all-ips []
  "Get all IP addresses from all network interfaces"
  (let [interfaces (NetworkInterface/getNetworkInterfaces)]
    (->> (enumeration-seq interfaces)
         (mapcat #(enumeration-seq (.getInetAddresses %)))
         (map #(.getHostAddress %))
         (remove nil?))))

(defn ipv4? [ip-str]
  "Check if IP string is IPv4"
  (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" ip-str))

(defn ipv6? [ip-str]
  "Check if IP string is IPv6"
  (str/includes? ip-str ":"))

(defn private? [ip-str]
  "Check if IP is private (RFC1918 for v4, ULA/link-local for v6)"
  (or (re-matches #"^10\..+" ip-str)
      (re-matches #"^172\.(1[6-9]|2[0-9]|3[01])\..+" ip-str)
      (re-matches #"^192\.168\..+" ip-str)
      (re-matches #"^127\..+" ip-str)
      (re-matches #"^fe80:.+" ip-str)
      (re-matches #"^fd[0-9a-f]{2}:.+" ip-str)))

(defn tailscale? [ip-str]
  "Check if IP is Tailscale (100.x range for v4)"
  (re-matches #"^100\.(6[4-9]|[7-9]\d|1[0-2]\d)\..+" ip-str))

(defn public? [ip-str]
  "Check if IP is public (not private, not loopback, not tailscale)"
  (and (not (private? ip-str))
       (not (tailscale? ip-str))))

(defn get-public-ipv4 []
  (->> (get-all-ips)
       (filter ipv4?)
       (filter public?)
       first))

(defn get-public-ipv6 []
  (->> (get-all-ips)
       (filter ipv6?)
       (filter public?)
       first))

(defn get-private-ipv4 []
  (->> (get-all-ips)
       (filter ipv4?)
       (filter private?)
       first))

(defn get-private-ipv6 []
  (->> (get-all-ips)
       (filter ipv6?)
       (filter private?)
       first))

(defn get-tailscale-ipv4 []
  (->> (get-all-ips)
       (filter ipv4?)
       (filter tailscale?)
       first))

(defn get-tailscale-ipv6 []
  (->> (get-all-ips)
       (filter ipv6?)
       (filter tailscale?)
       first))

;; --- HMAC Signature ---

(defn base64-encode [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn hmac-sha512 [key-str message]
  "Generate HMAC-SHA512 signature"
  (let [key-bytes (.getBytes (str/trim key-str) "UTF-8")
        secret-key (SecretKeySpec. key-bytes "HmacSHA512")
        mac (Mac/getInstance "HmacSHA512")]
    (.init mac secret-key)
    (base64-encode (.doFinal mac (.getBytes message "UTF-8")))))

(defn build-request-string [method uri timestamp body]
  "Build the request string for HMAC signing"
  (str (str/upper-case (name method)) uri timestamp body))

;; --- HTTP Client ---

(defn epoch-timestamp []
  (.getEpochSecond (Instant/now)))

(defn make-authenticated-request
  [method url body hmac-key verbose]
  "Make an authenticated HTTP request with HMAC signature"
  (let [timestamp (str (epoch-timestamp))
        uri (str "/" (str/join "/" (drop 3 (str/split url #"/"))))
        req-str (build-request-string method uri timestamp body)
        signature (hmac-sha512 hmac-key req-str)]
    (when verbose
      (println (str "Making " (name method) " request to " url)))
    (try
      (http/request
       {:method method
        :url url
        :headers {"access-signature" signature
                  "access-timestamp" timestamp
                  "content-type" "application/json"}
        :body body
        :throw false})
      (catch Exception e
        (when verbose
          (println "Request failed:" (.getMessage e)))
        {:status 500 :body (str "Error: " (.getMessage e))}))))

;; --- DDNS Update ---

(defn build-batch-url [server port domain host]
  (format "http://%s:%d/api/v2/domain/%s/host/%s/batch"
          server port domain host))

(defn send-batch-update!
  [{:keys [server port domain hostname hmac-key verbose]} data]
  "Send a batch update to the server"
  (let [url (build-batch-url server port domain hostname)
        body (json/generate-string data)]
    (when verbose
      (println (str "Sending batch update to " server " for " hostname "." domain))
      (println "Data:" data))
    (make-authenticated-request :put url body hmac-key verbose)))

;; --- State Management ---

(def state-file-path "/var/lib/nexus-client/last-state.edn")

(defn ensure-state-dir! []
  (io/make-parents state-file-path))

(defn load-last-state []
  "Load the last reported state from disk"
  (try
    (when (.exists (io/file state-file-path))
      (read-string (slurp state-file-path)))
    (catch Exception _
      nil)))

(defn save-state! [state]
  "Save the current state to disk"
  (ensure-state-dir!)
  (spit state-file-path (pr-str state)))

(defn state-changed? [old-state new-state]
  "Check if state has changed"
  (not= old-state new-state))

;; --- SSHFP Processing ---

(defn load-sshfps [sshfp-files]
  "Load SSHFP records from files"
  (when (seq sshfp-files)
    (->> sshfp-files
         (mapcat #(str/split-lines (slurp %)))
         (remove str/blank?)
         vec)))

;; --- Main Logic ---

(defn get-current-state [opts]
  "Get the current IP addresses and SSHFPs"
  (let [ip-type (:ip-type opts)
        get-v4 (case ip-type
                 :public (get-public-ipv4)
                 :private (get-private-ipv4)
                 :tailscale (get-tailscale-ipv4))
        get-v6 (case ip-type
                 :public (get-public-ipv6)
                 :private (get-private-ipv6)
                 :tailscale (get-tailscale-ipv6))
        sshfps (load-sshfps (:sshfp-files opts))]
    (cond-> {}
      (and (:ipv4 opts) get-v4) (assoc :ipv4 get-v4)
      (and (:ipv6 opts) get-v6) (assoc :ipv6 get-v6)
      (seq sshfps) (assoc :sshfps sshfps))))

(defn report-to-servers! [opts current-state]
  "Report current state to all configured servers and domains"
  (doseq [domain (:domains opts)
          server (:servers opts)]
    (let [config (assoc opts
                        :server server
                        :domain domain)
          ;; Get aliases for this domain
          aliases (get-in opts [:aliases domain] [])
          ;; Report for main hostname
          _ (let [response (send-batch-update! config current-state)]
              (when (:verbose opts)
                (println (format "Response from %s for %s.%s: status=%d"
                                 server (:hostname opts) domain (:status response))))
              (when (not= 200 (:status response))
                (println (format "ERROR: Failed to update %s.%s on %s: %s"
                                 (:hostname opts) domain server (:body response)))))]
      ;; Report for each alias
      (doseq [alias aliases]
        (let [alias-config (assoc config :hostname alias)
              response (send-batch-update! alias-config current-state)]
          (when (:verbose opts)
            (println (format "Response from %s for %s.%s (alias): status=%d"
                             server alias domain (:status response))))
          (when (not= 200 (:status response))
            (println (format "ERROR: Failed to update alias %s.%s on %s: %s"
                             alias domain server (:body response)))))))))

(defn update-if-changed! [opts]
  "Update DNS records only if state has changed"
  (let [last-state (load-last-state)
        current-state (get-current-state opts)]
    (when (:verbose opts)
      (println "Last state:" last-state)
      (println "Current state:" current-state))
    (if (state-changed? last-state current-state)
      (do
        (when (:verbose opts)
          (println "State changed, updating servers..."))
        (report-to-servers! opts current-state)
        (save-state! current-state)
        (println "Update completed successfully"))
      (when (:verbose opts)
        (println "No changes detected, skipping update")))))

(defn parse-aliases [alias-strs]
  "Parse alias strings in format 'alias:domain' into a map of {domain [alias1 alias2...]}"
  (reduce (fn [acc alias-str]
            (let [[alias domain] (str/split alias-str #":")]
              (update acc domain (fnil conj []) alias)))
          {}
          alias-strs))

;; --- CLI ---

(def cli-spec
  {:hostname {:desc "Hostname of this machine"
              :default (.getHostName (InetAddress/getLocalHost))}
   :domains {:desc "Domains to update (comma-separated or multiple -d flags)"
             :coerce []}
   :servers {:desc "DDNS servers (comma-separated or multiple -s flags)"
             :coerce []}
   :aliases {:desc "Aliases in format 'alias:domain' (can specify multiple)"
             :coerce []
             :default []}
   :port {:desc "Server port"
          :default 80
          :coerce :int}
   :key-file {:desc "HMAC key file path"
              :required true}
   :ipv4 {:desc "Report IPv4 address"
          :default true
          :coerce :boolean}
   :ipv6 {:desc "Report IPv6 address"
          :default true
          :coerce :boolean}
   :sshfp-files {:desc "SSHFP files to report"
                 :coerce []
                 :default []}
   :tailscale {:desc "Report Tailscale IPs"
               :default false
               :coerce :boolean}
   :private {:desc "Report private IPs"
             :default false
             :coerce :boolean}
   :verbose {:desc "Verbose output"
             :default false
             :coerce :boolean}
   :help {:desc "Show help"
          :alias :h}})

(defn print-usage []
  (println "Usage: nexus-client [options]")
  (println "\nOptions:")
  (doseq [[k spec] cli-spec]
    (println (format "  --%s%s: %s%s"
                     (name k)
                     (if-let [a (:alias spec)]
                       (str ", -" (name a))
                       "")
                     (:desc spec)
                     (if-let [d (:default spec)]
                       (str " (default: " d ")")
                       "")))))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:spec cli-spec})]
    (when (or (:help opts) (empty? args))
      (print-usage)
      (System/exit 0))
    
    (when (empty? (:servers opts))
      (println "ERROR: At least one server must be specified (--servers)")
      (System/exit 1))
    
    (when (empty? (:domains opts))
      (println "ERROR: At least one domain must be specified (--domains)")
      (System/exit 1))
    
    (when-not (:key-file opts)
      (println "ERROR: HMAC key file must be specified (--key-file)")
      (System/exit 1))
    
    (let [hmac-key (str/trim (slurp (:key-file opts)))
          ip-type (cond (:tailscale opts) :tailscale
                        (:private opts) :private
                        :else :public)
          aliases-map (parse-aliases (:aliases opts))
          final-opts (assoc opts
                            :hmac-key hmac-key
                            :ip-type ip-type
                            :aliases aliases-map)]
      (try
        (update-if-changed! final-opts)
        (System/exit 0)
        (catch Exception e
          (println "FATAL ERROR:" (.getMessage e))
          (when (:verbose final-opts)
            (.printStackTrace e))
          (System/exit 1))))))

;; Run main if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
