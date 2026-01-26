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

(defn- set-host-ipv4 
  "Handler for setting a host's IPv4 address record"
  [store metrics-registry]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string payload)]
       (when (not (ip/ipv4? ip))
         {:status 400
          :body (format "rejected: not a v4 IP: %s" payload)})
       (store/set-host-ipv4 store domain host ip)
       (metrics/inc-counter! metrics-registry :ipv4-updates)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" payload)})
     (catch Exception e
       (log/log-error "set-host-ipv4-failed" e 
                     {:domain domain
                      :host host
                      :ip (str payload)})
       {:status 500
        :body "Internal server error"}))))

(defn- set-host-ipv6
  "Handler for setting a host's IPv6 address record"
  [store metrics-registry]  
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string payload)]
       (when (not (ip/ipv6? ip))
         {:status 400
          :body (format "rejected: not a v6 IP: %s" payload)})
       (store/set-host-ipv6 store domain host ip)
       (metrics/inc-counter! metrics-registry :ipv6-updates)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" payload)})
     (catch Exception e
       (log/log-error "set-host-ipv6-failed" e
                     {:domain domain
                      :host host 
                      :ip (str payload)})
       {:status 500
        :body "Internal server error"}))))

(defn- valid-sshfp? [sshfp]
  (not (nil? (re-matches #"^[12346] [12] [0-9a-fA-F]{16,256}$" sshfp))))

(defn- set-host-sshfps
  "Handler for setting a host's SSHFP records"
  [store metrics-registry]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [;; If payload is a string, split by newlines; otherwise treat as collection
           sshfp-lines (if (string? payload)
                         (->> (str/split-lines payload)
                              (map str/trim)
                              (filter #(not (str/blank? %))))
                         payload)]
       (if (not (every? valid-sshfp? sshfp-lines))
         {:status 400 :body (str "rejected: invalid sshfp: "
                                 (some (comp not valid-sshfp?) sshfp-lines))}
         (do (store/set-host-sshfps store domain host sshfp-lines)
             (metrics/inc-counter! metrics-registry :sshfp-updates)
             {:status 200 :body (str/join "\n" sshfp-lines)})))
     (catch Exception e
       (log/log-error "set-host-sshfps-failed" e
                     {:domain domain
                      :host host})
       {:status 500
        :body "Internal server error"}))))

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

(defn- set-host-batch
  "Handler for batch updating multiple record types for a host"
  [store metrics-registry]
  (fn [{:keys [payload]
       {:keys [host domain]} :path-params}]
    (try+
     (let [errors (atom [])
           validated-data (atom {})]
       ;; Validate IPv4 if present
       (when-let [ipv4-str (:ipv4 payload)]
         (try
           (let [ip (ip/from-string ipv4-str)]
             (if (ip/ipv4? ip)
               (swap! validated-data assoc :ipv4 ip)
               (swap! errors conj (format "not a valid IPv4: %s" ipv4-str))))
           (catch IllegalArgumentException _
             (swap! errors conj (format "failed to parse IPv4: %s" ipv4-str)))))
       
       ;; Validate IPv6 if present
       (when-let [ipv6-str (:ipv6 payload)]
         (try
           (let [ip (ip/from-string ipv6-str)]
             (if (ip/ipv6? ip)
               (swap! validated-data assoc :ipv6 ip)
               (swap! errors conj (format "not a valid IPv6: %s" ipv6-str))))
           (catch IllegalArgumentException _
             (swap! errors conj (format "failed to parse IPv6: %s" ipv6-str)))))
       
        ;; Validate SSHFPs if present
        (when-let [sshfps (:sshfps payload)]
          (let [;; If sshfps is a string, split by newlines; otherwise treat as collection
                sshfp-lines (if (string? sshfps)
                              (->> (str/split-lines sshfps)
                                   (map str/trim)
                                   (filter #(not (str/blank? %))))
                              sshfps)]
            (if (every? valid-sshfp? sshfp-lines)
              (swap! validated-data assoc :sshfps sshfp-lines)
              (swap! errors conj (str "invalid sshfp records: "
                                      (filter (comp not valid-sshfp?) sshfp-lines))))))
       
        ;; Return errors if any validation failed
        (if (seq @errors)
          {:status 400 :body {:errors @errors}}
          ;; Otherwise perform batch update
          (do (store/set-host-batch store domain host @validated-data)
              (metrics/inc-counter! metrics-registry :batch-updates)
              {:status 200 :body (update-vals @validated-data str)})))
     (catch Exception e
       (log/log-error "set-host-batch-failed" e
                     {:domain domain
                      :host host
                      :payload payload})
       {:status 500
        :body "Internal server error"}))))

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
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- create-challenge-record
  "Handler for creating a new ACME challenge record"
  [store metrics-registry]
  (fn [{{:keys [host secret]}         :payload
       {:keys [domain challenge-id]} :path-params}]
    (try+
     (do (store/create-challenge-record store domain host challenge-id secret)
         (metrics/inc-counter! metrics-registry :challenge-creates)
         {:status 200 :body (str challenge-id)})
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occured: %s"
                              (.toString e))}}))))

(defn- delete-challenge-record
  "Handler for deleting an ACME challenge record"
  [store metrics-registry]
  (fn [{{:keys [domain challenge-id]} :path-params}]
    (try+
     (do (store/delete-challenge-record store domain (parse-uuid challenge-id))
         (metrics/inc-counter! metrics-registry :challenge-deletes)
         {:status 200 :body (str challenge-id)})
     (catch Exception e
       (when (:verbose store)
         (println (format "an unknown error has occurred: %s" (.toString e))))
       {:status 500
        :body {:error (format "an unknown error has occured: %s"
                              (.toString e))}}))))

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

(defn- decode-body
  "Middleware to parse the request body as JSON or plain text based on content-type"
  [handler]
  (fn [{:keys [body headers] :as req}]
    (if body
      (let [body-str (slurp body)
            content-type (get headers :content-type "text/plain")
            payload (cond
                      (= body-str "") {}
                      (str/includes? content-type "application/json")
                      (json/read-str body-str {:key-fn keyword})
                      :else body-str)]
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

(defn pthru [msg o] (println (format "%s: %s" msg o)) o)

(defn- make-timing-validator
  "Create middleware to validate request timestamps are within a max difference"
  [max-diff]
  (fn [handler]
    (fn [{{:keys [access-timestamp]} :headers
         :as req}]
      (if (nil? access-timestamp)
        { :status 401 :body "rejected: missing request timestamp" }
        (let [timestamp (-> access-timestamp
                            (Long/parseLong))
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

(defn create-app
  "Create the Nexus server app with the given configuration"
  [& {:keys [host-authenticator
             challenge-authenticator
             data-store
             max-delay
             verbose
             host-mapper]
      :or   {max-delay 60
             verbose   false}}]
  (let [metrics-registry (metrics/initialize-metrics)]
    (log/setup-logging! {:verbose verbose})
    (log/info! {:event "server-starting"})
    (ring/ring-handler
     (ring/router [["/" {:get {:handler (serve-web-ui)}}]
                   ["/metrics" {:get {:handler (serve-metrics metrics-registry)}}]
                   ["/api"
                    ["/v2" {:middleware [keywordize-headers
                                         decode-body 
                                         encode-body
                                         (log-requests verbose)
                                         (metrics/time-request metrics-registry)]}
                     ["/health"  {:get {:handler (fn [_] {:status 200 :body "OK"})}}]
                     ["/records" {:get {:handler (list-records data-store)}}]
                     ["/domain/:domain"
                      ["/challenges" {:middleware [(make-challenge-signature-authenticator verbose challenge-authenticator metrics-registry)
                                                   (make-timing-validator max-delay)]}
                       ["/list" {:get {:handler (get-challenge-records data-store)}}]]
                      ["/challenge" {:middleware [(make-challenge-signature-authenticator verbose challenge-authenticator metrics-registry)
                                                  (make-timing-validator max-delay)]}
                       ["/:challenge-id" {:put    {:handler (create-challenge-record data-store metrics-registry)}
                                          :delete {:handler (delete-challenge-record data-store metrics-registry)}}]]
                       ["/host" {:middleware [(make-host-signature-authenticator verbose host-authenticator host-mapper metrics-registry)
                                              (make-timing-validator max-delay)]}
                        ["/:host"
                         ["/ipv4"   {:put {:handler (set-host-ipv4 data-store metrics-registry)}
                                     :get {:handler (get-host-ipv4 data-store)}}]
                         ["/ipv6"   {:put {:handler (set-host-ipv6 data-store metrics-registry)}
                                     :get {:handler (get-host-ipv6 data-store)}}]
                         ["/sshfps" {:put {:handler (set-host-sshfps data-store metrics-registry)}
                                     :get {:handler (get-host-sshfps data-store)}}]
                         ["/batch"  {:put {:handler (set-host-batch data-store metrics-registry)}}]]]]]]])
     (ring/create-default-handler))))
