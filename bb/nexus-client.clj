#!/usr/bin/env bb

(ns nexus-client
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.net InetAddress]
           [java.time Instant]))

;; --- IP Detection ---

(defn get-all-ips
  "Get all IP addresses from all network interfaces using ip addr command"
  []
  (try
    (let [result (process/shell {:out :string :err :string :continue true} "ip" "addr")]
      (if (zero? (:exit result))
        (->> (:out result)
             str/split-lines
             (keep (fn [line]
                     (let [line (str/trim line)]
                       (cond
                         (str/starts-with? line "inet ")
                         (second (re-find #"inet ([0-9\.]+)" line))
                         (str/starts-with? line "inet6 ")
                         (second (re-find #"inet6 ([0-9a-f:]+)" line))))))
             (remove nil?))
        (throw (ex-info (str "'ip addr' failed with exit code " (:exit result))
                        {:exit (:exit result) :stderr (:err result)}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info (str "Failed to run 'ip addr': " (.getMessage e))
                      {:cause e})))))

(defn ipv4?
  "Check if IP string is IPv4"
  [ip-str]
  (re-matches #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$" ip-str))

(defn ipv6?
  "Check if IP string is IPv6"
  [ip-str]
  (str/includes? ip-str ":"))

;; IP classification.
;;
;; We let java.net.InetAddress do the bit-level math behind the well-known
;; reserved ranges (loopback, link-local, RFC1918, multicast, ...) rather
;; than matching address strings by hand -- string matching misses
;; equivalent spellings like the fully-expanded ::1 (0:0:0:0:0:0:0:1) and
;; is easy to get subtly wrong at range boundaries. The two ranges
;; InetAddress does not recognize -- IPv6 ULA (fc00::/7) and Tailscale's
;; CGNAT block (100.64.0.0/10, RFC 6598) -- are handled with an explicit
;; CIDR check.
;;
;; NOTE: the isXxxAddress/getAddress methods are called on values
;; type-hinted as ^InetAddress on purpose. Babashka's native image does not
;; register reflection metadata for the Inet4Address/Inet6Address
;; subclasses, so an un-hinted (reflective) call throws
;; MissingReflectionRegistrationError at runtime.

(defn ^InetAddress parse-ip
  "Parse a literal IP string into an InetAddress, or nil if it is not a valid
   numeric address. Any zone id (e.g. fe80::1%eth0) is stripped. getByName on
   a numeric literal does not trigger a DNS lookup."
  [ip-str]
  (try
    (InetAddress/getByName (first (str/split ip-str #"%")))
    (catch Exception _ nil)))

(defn in-cidr?
  "True if ip-str falls inside the CIDR block network-str/prefix-len, compared
   byte-by-byte under the prefix mask. Works for both IPv4 and IPv6; a
   mismatched address family is never a match."
  [ip-str network-str prefix-len]
  (let [a (parse-ip ip-str)
        n (parse-ip network-str)]
    (boolean
     (when (and a n)
       (let [ab (.getAddress ^InetAddress a)
             nb (.getAddress ^InetAddress n)]
         (and (= (alength ab) (alength nb))
              (loop [bits prefix-len i 0]
                (cond
                  (<= bits 0) true
                  (>= bits 8) (if (= (aget ab i) (aget nb i))
                                (recur (- bits 8) (inc i))
                                false)
                  :else (let [mask (bit-and 0xff (bit-shift-left 0xff (- 8 bits)))]
                          (= (bit-and (aget ab i) mask)
                             (bit-and (aget nb i) mask)))))))))))

(defn reserved?
  "True for addresses that must never be reported in any mode: loopback
   (127.0.0.0/8, ::1), link-local (169.254.0.0/16, fe80::/10), unspecified
   (0.0.0.0, ::), and multicast (224.0.0.0/4, ff00::/8). Unparseable input is
   treated as reserved so malformed addresses are never reported."
  [ip-str]
  (if-let [^InetAddress addr (parse-ip ip-str)]
    (or (.isLoopbackAddress addr)
        (.isLinkLocalAddress addr)
        (.isAnyLocalAddress addr)
        (.isMulticastAddress addr))
    true))

(defn private?
  "True for private-network addresses suitable for a private domain: RFC1918
   (10/8, 172.16/12, 192.168/16) and IPv6 ULA (fc00::/7). InetAddress covers
   RFC1918 via isSiteLocalAddress but not ULA, so ULA is matched explicitly."
  [ip-str]
  (if-let [^InetAddress addr (parse-ip ip-str)]
    (or (.isSiteLocalAddress addr)
        (in-cidr? ip-str "fc00::" 7))
    false))

(defn tailscale?
  "True for Tailscale's CGNAT range, 100.64.0.0/10 (RFC 6598)."
  [ip-str]
  (in-cidr? ip-str "100.64.0.0" 10))

(defn public?
  "True only for globally routable addresses: not reserved, not a
   private-network address, and not Tailscale."
  [ip-str]
  (and (not (reserved? ip-str))
       (not (private? ip-str))
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

(defn decode-key
  "Decode a key string in 'ALGO:BASE64' format (as produced by nexus-generate-key) into a SecretKeySpec."
  [key-str]
  (let [[algo encoded-key] (str/split (str/trim key-str) #":" 2)
        key-bytes (.decode (Base64/getDecoder) encoded-key)]
    (SecretKeySpec. key-bytes algo)))

(defn hmac-sign
  "Generate an HMAC signature using the encoded key string."
  [key-str message]
  (let [key (decode-key key-str)
        mac (doto (Mac/getInstance (.getAlgorithm key))
              (.init key))]
    (base64-encode (.doFinal mac (.getBytes message "UTF-8")))))

(defn build-request-string
  "Build the request string for HMAC signing"
  [method uri timestamp body]
  (str (str/upper-case (name method)) uri timestamp body))

;; --- HTTP Client ---

(defn epoch-timestamp []
  (.getEpochSecond (Instant/now)))

(defn make-authenticated-request
  "Make an authenticated HTTP request with HMAC signature"
  [method url body hmac-key verbose]
  (let [timestamp (str (epoch-timestamp))
        uri (str "/" (str/join "/" (drop 3 (str/split url #"/"))))
        req-str (build-request-string method uri timestamp body)
        signature (hmac-sign hmac-key req-str)]
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
  (let [scheme (if (= port 443) "https" "http")]
    (format "%s://%s:%d/api/v2/domain/%s/host/%s/batch"
            scheme server port domain host)))

(defn send-batch-update!
  "Send a PUT update. On success returns parsed response body (the confirmed server state);
   on failure returns a map with :error."
  [{:keys [server port domain hostname hmac-key verbose]} data]
  (let [url  (build-batch-url server port domain hostname)
        body (json/generate-string data)]
    (when verbose
      (println "Data:" data))
    (let [response (make-authenticated-request :put url body hmac-key verbose)]
      (if (= 200 (:status response))
        (json/parse-string (:body response) true)
        {:error (:body response)}))))

;; --- Local State Cache ---

(defn load-cached-state [state-file]
  (try
    (when (.exists (io/file state-file))
      (edn/read-string (slurp state-file)))
    (catch Exception _
      nil)))

(defn save-state! [state-file state]
  (io/make-parents state-file)
  (spit state-file (pr-str state)))

;; --- SSHFP Processing ---

(defn load-sshfps
  "Load SSHFP records from files"
  [sshfp-files]
  (when (seq sshfp-files)
    (->> sshfp-files
         (mapcat #(str/split-lines (slurp %)))
         (remove str/blank?)
         vec)))

;; --- Main Logic ---

(defn get-current-state
  "Get the current IP addresses and SSHFPs"
  [opts]
  (let [ip-type (:ip-type opts)
        v4 (case ip-type
             :public (get-public-ipv4)
             :private (get-private-ipv4)
             :tailscale (get-tailscale-ipv4))
        v6 (case ip-type
             :public (get-public-ipv6)
             :private (get-private-ipv6)
             :tailscale (get-tailscale-ipv6))
        sshfps (load-sshfps (:sshfp-files opts))]
    (cond-> {}
      (and (:ipv4 opts) v4) (assoc :ipv4 v4)
      (and (:ipv6 opts) v6) (assoc :ipv6 v6)
      (seq sshfps) (assoc :sshfps sshfps))))

(defn update-host!
  "Send a PUT for one hostname. Returns the server-confirmed state map on success, nil on failure."
  [opts server domain hostname label current-state]
  (let [config   (assoc opts :server server :domain domain :hostname hostname)
        result   (send-batch-update! config current-state)]
    (if (:error result)
      (do (binding [*out* *err*]
            (println (format "ERROR: Failed to update %s.%s%s on %s: %s"
                             hostname domain label server (:error result))))
          nil)
      (do (when (:verbose opts)
            (println (format "Updated %s.%s%s on %s" hostname domain label server)))
          result))))

(defn update-all-servers!
  "Send PUT to all servers and domains.
   Returns [failures confirmed-state] where confirmed-state is the server response
   from the first successful main-hostname PUT (nil if all failed)."
  [opts current-state]
  (reduce (fn [[failures confirmed] [server domain]]
            (let [aliases (get-in opts [:aliases domain] [])
                  main-result (update-host! opts server domain (:hostname opts) "" current-state)
                  alias-failures (->> aliases
                                      (map #(update-host! opts server domain % " (alias)" current-state))
                                      (remove some?)
                                      count)]
              [(+ failures (if main-result 0 1) alias-failures)
               (or confirmed main-result)]))
          [0 nil]
          (for [domain (:domains opts) server (:servers opts)] [server domain])))

(defn run-update!
  "Update DNS records if local state differs from the cached confirmed server state.
   Returns number of failures."
  [opts]
  (let [state-file    (:state-file opts)
        cached-state  (load-cached-state state-file)
        current-state (get-current-state opts)]
    (when (:verbose opts)
      (println "Cached state:" cached-state)
      (println "Current state:" current-state))
    (if (= current-state cached-state)
      (do (when (:verbose opts)
            (println "State unchanged, skipping update"))
          0)
      (do (println "State changed, updating servers...")
          (let [[failures confirmed] (update-all-servers! opts current-state)]
            (if (zero? failures)
              (do (save-state! state-file confirmed)
                  (println "Update completed successfully")
                  0)
              (do (binding [*out* *err*]
                    (println (format "Update completed with %d failure(s) -- cache not updated, will retry" failures)))
                  failures)))))))

(defn parse-aliases
  "Parse alias strings in format 'alias:domain' into a map of {domain [alias1 alias2...]}"
  [alias-strs]
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
   :state-file {:desc "Path to state cache file"
               :default (str (or (System/getenv "CACHE_DIRECTORY") "/var/cache/nexus-client")
                             "/state.edn")}
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
        (let [failures (run-update! final-opts)]
          (System/exit (min failures 1)))
        (catch Exception e
          (binding [*out* *err*]
            (println "FATAL ERROR:" (.getMessage e)))
          (when (:verbose final-opts)
            (.printStackTrace e))
          (System/exit 1))))))

;; Run main if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
