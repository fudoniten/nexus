(ns nexus.metrics
  (:require [metrics.core :as metrics]
            [metrics.timers :as timers]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [metrics.meters :as meters]
            [metrics.gauges :as gauges]
            [nexus.logging :as log]
            [iapetos.export :as export]))

(defn initialize-metrics []
  (log/info! "Initializing Nexus metrics")  
  (let [registry (metrics/new-registry)]
    {
     ::registry registry
     ::counters {:errors (counters/counter registry "errors")
                 :total-requests (counters/counter registry "http_requests_total")
                 :ipv4-updates (counters/counter registry "ipv4_updates_total")
                 :ipv6-updates (counters/counter registry "ipv6_updates_total")
                 :sshfp-updates (counters/counter registry "sshfp_updates_total")
                 :batch-updates (counters/counter registry "batch_updates_total")
                 :challenge-creates (counters/counter registry "challenge_creates_total")
                 :challenge-deletes (counters/counter registry "challenge_deletes_total")
                 :auth-failures (counters/counter registry "auth_failures_total")}
     ::meters {:request-rate (meters/meter registry "request_rate")}
     ::unique-ips (atom #{})
     }))

(defn get-counter [{counters ::counters} counter]
  (get counters counter))

(defn get-meter [{meters ::meters} meter]
  (get meters meter))

(defn inc-counter! [metrics-registry counter-key]
  (when-let [counter (get-counter metrics-registry counter-key)]
    (counters/inc! counter)))

(defn mark-meter! [metrics-registry meter-key]
  (when-let [meter (get-meter metrics-registry meter-key)]
    (meters/mark! meter)))

(defn track-client-ip! [metrics-registry ip]
  (when-let [unique-ips (::unique-ips metrics-registry)]
    (swap! unique-ips conj ip)))

(defn get-unique-ip-count [metrics-registry]
  (when-let [unique-ips (::unique-ips metrics-registry)]
    (count @unique-ips)))

(defn metrics-handler [{registry ::registry :as metrics-registry}]
  (let [unique-ip-count (get-unique-ip-count metrics-registry)]
    ;; Register a gauge for unique IPs if not already registered
    (try
      (gauges/gauge-fn registry "unique_client_ips" (fn [] unique-ip-count))
      (catch Exception _))
    (export/text-format registry)))

(defn time-request [metrics-registry]
  (fn [handler]
    (fn [request]
      (let [{registry ::registry} metrics-registry
            timer (timers/timer registry "request_duration_seconds")]
        (try
          ;; Increment total requests counter
          (inc-counter! metrics-registry :total-requests)
          (mark-meter! metrics-registry :request-rate)
          
          ;; Track client IP
          (when-let [client-ip (or (get-in request [:headers :x-forwarded-for])
                                   (get-in request [:headers :x-real-ip])
                                   (:remote-addr request))]
            (track-client-ip! metrics-registry client-ip))
          
          (let [response (timers/time! timer (handler request))]
            (when-let [req-size (get-in request [:headers :content-length])]
              (histograms/update! (histograms/histogram registry "request_size_bytes") (Long/parseLong req-size)))
            (when-let [res-size (get-in response [:headers :content-length])]
              (histograms/update! (histograms/histogram registry "response_size_bytes") (Long/parseLong res-size)))
            response)
          (catch Exception e
            (log/warn! e "Error in timed request")
            (inc-counter! metrics-registry :errors)
            (throw e)))))))
