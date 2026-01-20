(ns nexus.logging
  (:require [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.data.json :as json]))

(defn setup-logging!
  "Configure logging with JSON output and appropriate levels"
  [{:keys [verbose]}]
  (timbre/merge-config!
   {:level (if verbose :debug :info)
    :appenders
    {:println (-> (appenders/println-appender)
                 (assoc :output-fn
                        (fn [{:keys [level msg_ timestamp_ ?err]}]
                          (json/write-str
                           {:timestamp (str timestamp_)
                            :level     (str level)
                            :message   (force msg_)
                            :error     (when ?err
                                       (.getMessage ?err))}))))}
    :timestamp-opts {:pattern "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"}}))

(defn log-request
  "Log incoming request details"
  [{:keys [request-method uri headers]}]
  (timbre/info
   {:event "incoming-request"
    :method (-> request-method name)
    :uri uri
    :server (get headers :x-forwarded-server)}))

(defn log-error
  "Log error with context"
  [event error & [context]]
  (timbre/error
   (merge
    {:event event
     :error (.getMessage error)
     :error-type (-> error class .getName)}
    context)))

(defmacro info!
  "Log an info message"
  [& args]
  `(timbre/info ~@args))

(defmacro warn!
  "Log a warning message"
  [& args]
  `(timbre/warn ~@args))
