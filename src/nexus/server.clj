(ns nexus.server
  (:require [reitit.ring :as ring]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [nexus.logging :as log]
            [nexus.metrics :as metrics]
            [taoensso.timbre :as timbre]
            [nexus.authenticator :as auth]
            [nexus.datastore :as store]
            [nexus.host-alias-map :as host-map]
            [slingshot.slingshot :refer [try+]]
            [fudo-clojure.ip :as ip]
            [fudo-clojure.common :refer [current-epoch-timestamp]]))

(defn- store-write
  "Run a datastore write, turning a change the DNS server refused (a CNAME
  conflict, an unknown zone, a bad value) into a 4xx response the client can
  act on, and anything else into a logged 500."
  [event context f]
  (try+
   (f)
   (catch store/store-error? e
     (log/warn! (merge {:event (str event "-refused")
                        :reason (::store/message e)}
                       context))
     (store/error-response e))
   (catch Exception e
     (log/log-error (str event "-failed") e context)
     {:status 500
      :body "Internal server error"})))

(defn- set-host-ip
  "Handler for setting a host's IPv4 or IPv6 address record"
  [store metrics-registry {:keys [family valid? write! counter event]}]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (store-write event {:domain domain :host host :ip (str payload)}
                 (fn []
                   (try
                     (let [ip (ip/from-string payload)]
                       (if (not (valid? ip))
                         {:status 400
                          :body (format "rejected: not a %s IP: %s" family payload)}
                         (do (write! store domain host ip)
                             (metrics/inc-counter! metrics-registry counter)
                             {:status 200 :body (str ip)})))
                     (catch IllegalArgumentException _
                       {:status 400
                        :body (format "rejected: failed to parse IP: %s" payload)}))))))

(defn- set-host-ipv4
  "Handler for setting a host's IPv4 address record"
  [store metrics-registry]
  (set-host-ip store metrics-registry {:family "v4" :valid? ip/ipv4?
                                       :write! store/set-host-ipv4
                                       :counter :ipv4-updates
                                       :event "set-host-ipv4"}))

(defn- set-host-ipv6
  "Handler for setting a host's IPv6 address record"
  [store metrics-registry]
  (set-host-ip store metrics-registry {:family "v6" :valid? ip/ipv6?
                                       :write! store/set-host-ipv6
                                       :counter :ipv6-updates
                                       :event "set-host-ipv6"}))

(defn- valid-sshfp? [sshfp]
  (not (nil? (re-matches #"^[12346] [12] [0-9a-fA-F]{16,256}$" sshfp))))

(defn- sshfp-lines
  "SSHFP records from a payload: a newline-separated string, or a collection."
  [payload]
  (if (string? payload)
    (->> (str/split-lines payload)
         (map str/trim)
         (remove str/blank?))
    payload))

(defn- set-host-sshfps
  "Handler for setting a host's SSHFP records. A name that is a CNAME is
  refused by the datastore (409); an alias from the host-alias map is just
  another name the host publishes its own records under."
  [store metrics-registry]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (store-write "set-host-sshfps" {:domain domain :host host}
                 (fn []
                   (let [lines (sshfp-lines payload)]
                     (if (not (every? valid-sshfp? lines))
                       {:status 400 :body (str "rejected: invalid sshfp: "
                                               (first (remove valid-sshfp? lines)))}
                       (do (store/set-host-sshfps store domain host lines)
                           (metrics/inc-counter! metrics-registry :sshfp-updates)
                           {:status 200 :body (str/join "\n" lines)})))))))

(defn- get-host-ipv4
  "Handler for retrieving a host's IPv4 address record"
  [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv4 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv4 for %s.%s not found." host domain)}))
     (catch Exception e
       (log/log-error "get-host-ipv4-failed" e
                     {:domain domain
                      :host host})
       {:status 500
        :body "Internal server error"}))))

(defn- get-host-ipv6
  "Handler for retrieving a host's IPv6 address record"
  [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv6 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv6 for %s.%s not found." host domain)}))
     (catch Exception e
       (log/log-error "get-host-ipv6-failed" e
                     {:domain domain
                      :host host})
       {:status 500
        :body "Internal server error"}))))

(defn- get-host-sshfps
  "Handler for retrieving a host's SSHFP records"
  [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [sshfps (store/get-host-sshfps store domain host)]
       (if sshfps
         {:status 200 :body sshfps}
         {:status 404 :body (format "SSHFP for %s.%s not found." host domain)}))
     (catch Exception e
       (log/log-error "get-host-sshfps-failed" e
                     {:domain domain
                      :host host})
       {:status 500
        :body "Internal server error"}))))

(defn- get-host-batch
  "Handler for retrieving all record types for a host in a single response"
  [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ipv4   (store/get-host-ipv4 store domain host)
           ipv6   (store/get-host-ipv6 store domain host)
           sshfps (store/get-host-sshfps store domain host)]
       {:status 200
        :body (cond-> {}
                ipv4   (assoc :ipv4 (str ipv4))
                ipv6   (assoc :ipv6 (str ipv6))
                (seq sshfps) (assoc :sshfps (vec sshfps)))})
     (catch Exception e
       (log/log-error "get-host-batch-failed" e {:domain domain :host host})
       {:status 500 :body "Internal server error"}))))

(defn- validate-batch
  "Validate a batch payload. Returns [errors data], data holding parsed IPs."
  [payload]
  (let [parse-ip (fn [k family valid?]
                   (when-let [s (get payload k)]
                     (try
                       (let [ip (ip/from-string s)]
                         (if (valid? ip)
                           [nil [k ip]]
                           [(format "not a valid %s: %s" family s)]))
                       (catch IllegalArgumentException _
                         [(format "failed to parse %s: %s" family s)]))))
        sshfps   (when-let [s (:sshfps payload)]
                   (let [lines (sshfp-lines s)]
                     (if (every? valid-sshfp? lines)
                       [nil [:sshfps lines]]
                       [(str "invalid sshfp records: " (vec (remove valid-sshfp? lines)))])))
        results  (remove nil? [(parse-ip :ipv4 "IPv4" ip/ipv4?)
                               (parse-ip :ipv6 "IPv6" ip/ipv6?)
                               sshfps])]
    [(keep first results)
     (into {} (keep second results))]))

(defn- set-host-batch
  "Handler for setting a host's full record state in one change. The payload
  is the whole state: a record type it leaves out is removed from the host,
  so an address the host no longer has does not linger in DNS."
  [store metrics-registry]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (store-write "set-host-batch" {:domain domain :host host :payload payload}
                 (fn []
                   (let [[errors data] (if (map? payload)
                                         (validate-batch payload)
                                         [["batch payload must be a JSON object"] {}])]
                     (cond
                       (seq errors)
                       {:status 400 :body {:errors (vec errors)}}

                       ;; The batch replaces the host's whole state, so one
                       ;; with no address at all would delete the host from
                       ;; DNS. That is almost always a client that ran before
                       ;; its network came up, not a host that wants to vanish.
                       (not (or (:ipv4 data) (:ipv6 data)))
                       {:status 400 :body {:errors ["batch has neither ipv4 nor ipv6; refusing to remove all of the host's addresses"]}}

                       :else
                       (do (store/set-host-batch store domain host data)
                           (metrics/inc-counter! metrics-registry :batch-updates)
                           {:status 200
                            :body (cond-> {}
                                    (:ipv4 data)   (assoc :ipv4 (str (:ipv4 data)))
                                    (:ipv6 data)   (assoc :ipv6 (str (:ipv6 data)))
                                    (:sshfps data) (assoc :sshfps (vec (:sshfps data))))})))))))

(defn- get-challenge-records
  "Handler for listing ACME challenge records for a domain"
  [store]
  (fn [{{:keys [domain]} :path-params}]
    (try+
     (let [challenge-records (store/get-challenge-records store domain)]
       (if challenge-records
         {:status 200 :body challenge-records}
         {:status 404 :body (format "Challenge records not found for domain %s" domain)}))
     (catch Exception e
       (log/log-error "get-challenge-records-failed" e {:domain domain})
       {:status 500
        :body {:error "Internal server error"}}))))

(defn- create-challenge-record
  "Handler for creating a new ACME challenge record"
  [store metrics-registry verbose]
  (fn [{payload                       :payload
       {:keys [domain challenge-id]} :path-params}]
    (let [{:keys [host secret]} (when (map? payload) payload)]
      (when verbose
        (println (format "challenge payload keys: %s" (when (map? payload) (keys payload)))))
      (store-write "create-challenge-record" {:domain domain :host host}
                   (fn []
                     (store/create-challenge-record store domain host challenge-id secret)
                     (metrics/inc-counter! metrics-registry :challenge-creates)
                     {:status 200 :body (str challenge-id)})))))

(defn- delete-challenge-record
  "Handler for deleting an ACME challenge record"
  [store metrics-registry]
  (fn [{{:keys [domain challenge-id]} :path-params}]
    (store-write "delete-challenge-record" {:domain domain :challenge-id challenge-id}
                 (fn []
                   (store/delete-challenge-record store domain (parse-uuid challenge-id))
                   (metrics/inc-counter! metrics-registry :challenge-deletes)
                   {:status 200 :body (str challenge-id)}))))

(defn- list-records
  "Handler for listing all DNS records"
  [store]
  (fn [_]
    (try+
     (let [records (store/list-all-records store)]
       {:status 200 :body {:records records}})
     (catch Exception e
       (log/log-error "list-records-failed" e {})
       {:status 500
        :body {:error "Failed to fetch records"}}))))

(defn- serve-web-ui
  "Handler for serving the web UI HTML"
  []
  (fn [_]
    (try
      (let [html-content (slurp (io/resource "web-ui.html"))]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body html-content})
      (catch Exception e
        (log/log-error "serve-web-ui-failed" e {})
        {:status 500
         :body "Failed to load web UI"}))))

(defn- serve-metrics
  "Handler for serving Prometheus metrics"
  [metrics-registry]
  (fn [_]
    (try
      {:status 200
       :headers {"Content-Type" "text/plain; version=0.0.4"}
       :body (metrics/metrics-handler metrics-registry)}
      (catch Exception e
        (log/log-error "serve-metrics-failed" e {})
        {:status 500
         :body "Failed to generate metrics"}))))

(def ^:private not-json
  "Sentinel for a body that is not (entirely) JSON. A parse can legitimately
  produce nil, so nil cannot serve as the signal."
  ::not-json)

(defn- parse-json
  "Parses body-str as a single JSON value, returning not-json unless the whole
  string is consumed.

  The whole-string check is the point: clojure.data.json reads one value and
  ignores whatever follows, so an SSHFP record -- which always begins with a
  digit -- parses as just that leading number. \"1 2 A1B2...\" came back as 1
  rather than failing, the plain-text fallback never ran, and the handler then
  tried to treat a Long as a sequence and returned 500."
  [body-str]
  (try
    ;; 64-char pushback buffer, matching what data.json's own read-str uses:
    ;; the default PushbackReader buffer holds a single character, and parsing
    ;; an object or array pushes back more than that, failing with "Pushback
    ;; buffer overflow".
    (with-open [reader (java.io.PushbackReader. (java.io.StringReader. body-str) 64)]
      (let [value (json/read reader {:key-fn keyword})]
        (if (str/blank? (slurp reader))
          value
          not-json)))
    (catch Exception _
      not-json)))

(defn- decode-body
  "Middleware to parse the request body. Attempts JSON parsing first;
  falls back to plain text if the body is not valid JSON."
  [handler]
  (fn [{:keys [body] :as req}]
    (if body
      (let [body-str (slurp body)
            parsed   (if (= body-str "") {} (parse-json body-str))
            payload  (if (= parsed not-json) body-str parsed)]
        (handler (-> req
                     (assoc :payload payload)
                     (assoc :body-str body-str))))
      (handler (-> req (assoc :body-str ""))))))

(defn- encode-body
  "Middleware to serialize the response body as JSON (unless it's already a string)"
  [handler]
  (fn [req]
    (let [resp (handler req)
          body (:body resp)]
      (assoc resp :body (if (string? body)
                          body
                          (json/write-str body))))))

(defn- keywordize-headers
  "Middleware to convert header names to keywords"
  [handler]
  (fn [req]
    (handler (update req :headers
                     (fn [headers] (update-keys headers keyword))))))

(defn- build-request-string [& {:keys [body method uri timestamp]}]
  (str (-> method (name) (str/upper-case))
       uri
       timestamp
       body))

(defn- authenticate-request [authenticator
                             signer
                             {:keys [body-str request-method uri]
                              {:keys [access-signature access-timestamp]} :headers}]
  (let [req-str (build-request-string :body body-str
                                      :method request-method
                                      :uri uri
                                      :timestamp access-timestamp)]
    (auth/validate-signature authenticator signer req-str access-signature)))

(defn- make-challenge-signature-authenticator
  "Create middleware to authenticate requests to the ACME challenge API"
  [verbose authenticator metrics-registry]
  (fn [handler]
    (fn [{{:keys [access-signature service]} :headers :as req}]
      (if (nil? access-signature)
        (do (when verbose (println "missing access signature, rejecting request"))
            (metrics/inc-counter! metrics-registry :auth-failures)
            { :status 406 :body "rejected: missing request signature" })
        (try+
         (if (authenticate-request authenticator (keyword service) req)
           (do (when verbose (println "accepted signature, proceeding"))
               (handler req))
           (do (when verbose (println "bad signature, rejecting request"))
               (metrics/inc-counter! metrics-registry :auth-failures)
               { :status 401 :body "rejected: request signature invalid" }))
         (catch [:type ::auth/missing-key] _
           (println (format "matching key not found for service %s, rejecting request" service))
           (metrics/inc-counter! metrics-registry :auth-failures)
           { :status 401 :body (format "rejected: missing key for service: %s" service) }))))))

(defn- make-host-signature-authenticator
  "Create middleware to authenticate requests to the host record API"
  [verbose authenticator host-mapper metrics-registry]
  (fn [handler]
    (fn [{{:keys [access-signature]} :headers
         {:keys [host domain]} :path-params
         :as req}]
      (if (nil? access-signature)
        (do (when verbose (println "missing access signature, rejecting request"))
            (metrics/inc-counter! metrics-registry :auth-failures)
            { :status 401 :body "rejected: missing request signature" })
        (try+
         (let [signer  (host-map/get-host host-mapper host domain)]
           (if (authenticate-request authenticator signer req)
             (do (when verbose (println "accepted signature, proceeding"))
                 (handler req))
             (do (when verbose (println "bad signature, rejecting request"))
                 (metrics/inc-counter! metrics-registry :auth-failures)
                 { :status 401 :body "rejected: request signature invalid" })))
         (catch [:type ::auth/missing-key] _
           (println "matching key not found, rejecting request")
           (metrics/inc-counter! metrics-registry :auth-failures)
           { :status 404 :body (str "rejected: missing key for host") }))))))

(defn- make-timing-validator
  "Create middleware to validate request timestamps are within a max difference"
  [max-diff]
  (fn [handler]
    (fn [{{:keys [access-timestamp]} :headers
         :as req}]
      (cond
        (nil? access-timestamp)
        { :status 401 :body "rejected: missing request timestamp" }

        (not (re-matches #"-?\d{1,18}" access-timestamp))
        { :status 400 :body "rejected: request timestamp is not an integer" }

        :else
        (let [timestamp (Long/parseLong access-timestamp)
              current-timestamp (current-epoch-timestamp)
              time-diff (abs (- timestamp current-timestamp))]
          (log/info! {:event "timing-validation"
                      :timestamp timestamp
                      :current-timestamp current-timestamp
                      :time-diff time-diff
                      :max-diff max-diff})
          (if (> time-diff max-diff)
            { :status 412 :body "rejected: request timestamp out of date" }
            (handler req)))))))

(defn- log-requests
  "Middleware to log incoming requests"
  [_]
  (fn [handler]
    (fn [req]
      (log/log-request req)
      (try+
       (let [result (handler req)]
         (timbre/debug {:event "request-completed"
                       :status (:status result)})
         result)
       (catch Exception e
         (log/warn! "request-failed" e
                       {:uri (:uri req)
                        :method (-> req :request-method name)})
         (throw e))))))

(defn- challenge-routes
  "Build the challenge-record routes (/challenges, /challenge)."
  [{:keys [challenge-authenticator data-store metrics-registry max-delay verbose]}]
  [["/challenges" {:middleware [(make-challenge-signature-authenticator verbose challenge-authenticator metrics-registry)
                                (make-timing-validator max-delay)]}
    ["/list" {:get {:handler (get-challenge-records data-store)}}]]
   ["/challenge" {:middleware [(make-challenge-signature-authenticator verbose challenge-authenticator metrics-registry)
                               (make-timing-validator max-delay)]}
    ["/:challenge-id" {:put    {:handler (create-challenge-record data-store metrics-registry verbose)}
                       :delete {:handler (delete-challenge-record data-store metrics-registry)}}]]])

(defn- host-routes
  "Build the host-record routes (/host)."
  [{:keys [host-authenticator data-store host-mapper metrics-registry max-delay verbose]}]
  [["/host" {:middleware [(make-host-signature-authenticator verbose host-authenticator host-mapper metrics-registry)
                          (make-timing-validator max-delay)]}
    ["/:host"
     ["/ipv4"   {:put {:handler (set-host-ipv4 data-store metrics-registry)}
                 :get {:handler (get-host-ipv4 data-store)}}]
     ["/ipv6"   {:put {:handler (set-host-ipv6 data-store metrics-registry)}
                 :get {:handler (get-host-ipv6 data-store)}}]
     ["/sshfps" {:put {:handler (set-host-sshfps data-store metrics-registry)}
                 :get {:handler (get-host-sshfps data-store)}}]
     ["/batch"  {:put {:handler (set-host-batch data-store metrics-registry)}
                :get {:handler (get-host-batch data-store)}}]]]])

(defn- api-domain-routes
  "Build the /domain/:domain route subtree (challenges, challenge, host).

  The host and challenge halves are mounted independently, each only when
  an authenticator for it was configured; the half with no authenticator is
  absent and 404s, rather than being mounted around a nil authenticator that
  would throw on the first request."
  [{:keys [host-authenticator challenge-authenticator] :as opts}]
  (into ["/domain/:domain"]
        (concat (when challenge-authenticator (challenge-routes opts))
                (when host-authenticator      (host-routes opts)))))

(defn- api-routes
  "Build the /v3 route subtree (health, records, domain). Requests are signed
  with Ed25519; the HMAC-signed /api/v2 is gone."
  [{:keys [metrics-registry verbose data-store] :as opts}]
  ["/v3" {:middleware [keywordize-headers
                       decode-body
                       encode-body
                       (log-requests verbose)
                       (metrics/time-request metrics-registry)]}
   ["/health"  {:get {:handler (fn [_] {:status 200 :body "OK"})}}]
   ["/records" {:get {:handler (list-records data-store)}}]
   (api-domain-routes opts)])

(defn create-app
  "Create the Nexus server app.

  host-authenticator and challenge-authenticator hold the Ed25519 public
  keys of hosts and of challenge clients. Either may be left out, and its
  routes are then not mounted, but not both."
  [& {:keys [host-authenticator
             challenge-authenticator
             data-store
             max-delay
             verbose
             host-mapper]
      :or   {max-delay 60
             verbose   false}}]
  (when-not (or host-authenticator challenge-authenticator)
    (throw (ex-info "no authenticators configured: the server would expose no API at all" {})))
  (let [metrics-registry (metrics/initialize-metrics)]
    (log/setup-logging! {:verbose verbose})
    (log/info! {:event "server-starting"})
    (ring/ring-handler
     (ring/router [["/" {:get {:handler (serve-web-ui)}}]
                   ["/metrics" {:get {:handler (serve-metrics metrics-registry)}}]
                   ["/api" (api-routes {:host-authenticator      host-authenticator
                                        :challenge-authenticator challenge-authenticator
                                        :data-store              data-store
                                        :host-mapper             host-mapper
                                        :metrics-registry        metrics-registry
                                        :max-delay               max-delay
                                        :verbose                 verbose})]])
     (ring/create-default-handler))))
