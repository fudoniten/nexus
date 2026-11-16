#!/usr/bin/env bb

(ns nexus-client
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [babashka.process :as process]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json])
  (:import [java.security KeyFactory Signature]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64]
           [java.net InetAddress]
           [java.time Instant]))

;; --- IP Detection ---

(defn parse-ip-addr-line
  "Parse one line of `ip -o addr` output into {:iface :ip}, or nil. A line
  looks like
    4: tailscale0    inet 100.101.102.103/32 scope global tailscale0\\ ...
  The interface may carry a peer suffix (veth0@if5), which is dropped."
  [line]
  (let [[_ iface family addr] (str/split (str/trim line) #"\s+")]
    (when (and iface addr (#{"inet" "inet6"} family))
      {:iface (-> iface (str/replace #":$" "") (str/split #"@") first)
       :ip    (first (str/split addr #"/"))})))

(defn get-all-addresses
  "Every address on every interface, as {:iface :ip} maps, from `ip -o addr`."
  []
  (try
    (let [result (process/shell {:out :string :err :string :continue true} "ip" "-o" "addr")]
      (if (zero? (:exit result))
        (->> (:out result)
             str/split-lines
             (keep parse-ip-addr-line))
        (throw (ex-info (str "'ip -o addr' failed with exit code " (:exit result))
                        {:exit (:exit result) :stderr (:err result)}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info (str "Failed to run 'ip -o addr': " (.getMessage e))
                      {:cause e})))))

(defn get-all-ips
  "Get all IP addresses from all network interfaces."
  []
  (map :ip (get-all-addresses)))

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
;; is easy to get subtly wrong at range boundaries. The ranges InetAddress
;; does not recognize -- IPv6 ULA (fc00::/7) and Tailscale's two blocks
;; (100.64.0.0/10 and fd7a:115c:a1e0::/48) -- are handled with an explicit
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

(defn tailscale?
  "True for Tailscale's address ranges: 100.64.0.0/10 (the RFC 6598 CGNAT
   block) for IPv4 and fd7a:115c:a1e0::/48 for IPv6.

   The range alone does not prove an address is Tailscale's: other overlays
   (Nebula, say) can be numbered out of the same CGNAT block. Tailscale mode
   therefore also checks the interface; see tailscale-address?."
  [ip-str]
  (or (in-cidr? ip-str "100.64.0.0" 10)
      (in-cidr? ip-str "fd7a:115c:a1e0::" 48)))

(defn private?
  "True for private-network addresses suitable for a private domain: RFC1918
   (10/8, 172.16/12, 192.168/16) and IPv6 ULA (fc00::/7), less Tailscale's
   IPv6 block, which sits inside the ULA range but belongs to Tailscale
   mode. InetAddress covers RFC1918 via isSiteLocalAddress but not ULA, so
   ULA is matched explicitly."
  [ip-str]
  (if-let [^InetAddress addr (parse-ip ip-str)]
    (and (or (.isSiteLocalAddress addr)
             (in-cidr? ip-str "fc00::" 7))
         (not (tailscale? ip-str)))
    false))

(defn tailscale-address?
  "True for an {:iface :ip} that is a Tailscale address: in Tailscale's
   ranges and, when tailscale-iface is non-blank, on that interface. The
   interface check is what keeps a Nebula (or other overlay) address in
   100.64.0.0/10 out of a Tailscale-only domain."
  [tailscale-iface {:keys [iface ip]}]
  (and (tailscale? ip)
       (or (str/blank? tailscale-iface)
           (= iface tailscale-iface))))

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

(defn get-tailscale-ips
  "[ipv4 ipv6]: this host's first Tailscale address of each family."
  [tailscale-iface]
  (let [ips (->> (get-all-addresses)
                 (filter (partial tailscale-address? tailscale-iface))
                 (map :ip))]
    [(first (filter ipv4? ips))
     (first (filter ipv6? ips))]))

;; --- Signature: Ed25519 (/api/v3) ---

(defn base64-encode [bytes]
  (.encodeToString (Base64/getEncoder) bytes))

(defn decode-private-key
  "Decode a private key string in 'Ed25519:BASE64' format (PKCS8-encoded, as
   produced by nexus-generate-key --keypair) into a PrivateKey.

   Rejects anything else (an old HMAC secret, above all) with a clear
   message: the server no longer accepts HMAC signatures."
  [key-str]
  (let [[algo encoded-key] (str/split (str/trim key-str) #":" 2)]
    (when (not= algo "Ed25519")
      (throw (ex-info
              (str "--private-key-file was given a " algo " key, not Ed25519 "
                   "-- HMAC keys and /api/v2 are gone; generate a keypair "
                   "with nexus-generate-key --keypair")
              {:algorithm algo})))
    (let [key-bytes (.decode (Base64/getDecoder) encoded-key)
          factory   (KeyFactory/getInstance "Ed25519")]
      (.generatePrivate factory (PKCS8EncodedKeySpec. key-bytes)))))

(defn ed25519-sign
  "Generate an Ed25519 signature using the encoded private key string."
  [private-key-str message]
  (let [private-key (decode-private-key private-key-str)
        signer (doto (Signature/getInstance "Ed25519")
                 (.initSign private-key)
                 (.update (.getBytes message "UTF-8")))]
    (base64-encode (.sign signer))))

(defn build-request-string
  "Build the request string for signing"
  [method uri timestamp body]
  (str (str/upper-case (name method)) uri timestamp body))

;; --- HTTP Client ---

(defn epoch-timestamp []
  (.getEpochSecond (Instant/now)))

(defn make-authenticated-request
  "Make an authenticated HTTP request, signed with sign-fn (a fn from
   message string to Base64 signature string -- ed25519-sign, partially
   applied to the encoded key)."
  [method url body sign-fn verbose]
  (let [timestamp (str (epoch-timestamp))
        uri (str "/" (str/join "/" (drop 3 (str/split url #"/"))))
        req-str (build-request-string method uri timestamp body)
        signature (sign-fn req-str)]
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

(defn build-batch-url [server port domain host api-version]
  (let [scheme (if (= port 443) "https" "http")]
    (format "%s://%s:%d/api/%s/domain/%s/host/%s/batch"
            scheme server port (name api-version) domain host)))

(defn send-batch-update!
  "Send a PUT update. On success returns parsed response body (the confirmed server state);
   on failure returns a map with :error."
  [{:keys [server port domain hostname sign-fn api-version verbose]} data]
  (let [url  (build-batch-url server port domain hostname api-version)
        body (json/generate-string data)]
    (when verbose
      (println "Data:" data))
    (let [response (make-authenticated-request :put url body sign-fn verbose)]
      (if (= 200 (:status response))
        (json/parse-string (:body response) true)
        {:error (:body response)}))))

;; --- Local State Cache ---

(defn load-cached-state
  "The server-confirmed state from the last successful update, or nil when
  there is none or it is older than max-age-seconds. The age limit is what
  repairs the server side: records deleted or fixed by hand there are
  re-sent within a day, instead of waiting for this host's IP to change."
  [state-file max-age-seconds]
  (try
    (let [f (io/file state-file)]
      (when (and (.exists f)
                 (< (- (System/currentTimeMillis) (.lastModified f))
                    (* 1000 max-age-seconds)))
        (edn/read-string (slurp f))))
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
        [v4 v6] (case ip-type
                  :public    [(get-public-ipv4) (get-public-ipv6)]
                  :private   [(get-private-ipv4) (get-private-ipv6)]
                  :tailscale (get-tailscale-ips (:tailscale-interface opts)))
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
        cached-state  (load-cached-state state-file (:max-cache-age opts))
        current-state (get-current-state opts)]
    (when (:verbose opts)
      (println "Cached state:" cached-state)
      (println "Current state:" current-state))
    (cond
      ;; An update replaces the host's whole record state, so sending one
      ;; with no address would remove the host from DNS. With no address
      ;; the network is most likely not up yet; the next run will see it.
      (not (or (:ipv4 current-state) (:ipv6 current-state)))
      (do (println "No address found yet, skipping update")
          0)

      (= current-state cached-state)
      (do (when (:verbose opts)
            (println "State unchanged, skipping update"))
          0)

      :else
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
   :private-key-file {:desc "Ed25519 private key file path, as written by nexus-generate-key --keypair. Required."}
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
   :tailscale-interface {:desc "With --tailscale, report only addresses on this interface (blank: any interface)"
                         :default "tailscale0"}
   :private {:desc "Report private IPs"
             :default false
             :coerce :boolean}
   :max-cache-age {:desc "Re-send even an unchanged state once the cache is this many seconds old"
                   :default 86400
                   :coerce :int}
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
    
    (when-not (:private-key-file opts)
      (println "ERROR: --private-key-file must be specified (an Ed25519 key, as written by nexus-generate-key --keypair)")
      (System/exit 1))

    (let [key-file-path (:private-key-file opts)
          key-content   (str/trim (slurp key-file-path))]
      ;; Keys are encoded "ALGO:BASE64...", e.g. "Ed25519:MCow...". Catch an
      ;; old HMAC key here, with an actionable message, rather than three
      ;; layers of exceptions deep.
      (when-not (str/starts-with? key-content "Ed25519:")
        (println (format "ERROR: --private-key-file (%s) does not look like an Ed25519 key (expected a value starting with \"Ed25519:\", as written by nexus-generate-key --keypair). HMAC keys and /api/v2 are no longer supported." key-file-path))
        (System/exit 1))

      (let [sign-fn (partial ed25519-sign key-content)
            api-version :v3
            ip-type (cond (:tailscale opts) :tailscale
                          (:private opts) :private
                          :else :public)
            aliases-map (parse-aliases (:aliases opts))
            final-opts (assoc opts
                              :sign-fn sign-fn
                              :api-version api-version
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
            (System/exit 1)))))))

;; Run main if executed as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
