(ns nexus.metrics
  (:require [metrics.core :as metrics]
            [metrics.timers :as timers]
            [metrics.counters :as counters]
            [metrics.histograms :as histograms]
            [nexus.logging :as log]
            [iapetos.export :as export]))

(defn initialize-metrics []
  (log/info! "Initializing Nexus metrics")  
  (let [registry (metrics/new-registry)]
    {
     ::registry registry
     ::counters {:errors (counters/counter registry "error-counter")}
     }))

(defn get-counter [{counters ::counters} counter]
  (get  counters counter))

(defn metrics-handler [{registry ::registry}]
  (export/text-format registry))

(defn time-request [{registry ::registry}]
  (fn [handler]
    (fn [request]
      (let [timer (timers/timer registry "request-timer")]
        (try
          (let [response (timers/time! timer (handler request))]
            (when-let [req-size (get-in request [:headers "content-length"])]
              (histograms/update! (histograms/histogram registry "request-size") (Long/parseLong req-size)))
            (when-let [res-size (get-in response [:headers "content-length"])]
              (histograms/update! (histograms/histogram registry "response-size") (Long/parseLong res-size)))
            response)
          (catch Exception e
            (log/warn! e "Error in timed request")
            (counters/inc! (counters/counter registry "errors"))
            (throw e)))))))
