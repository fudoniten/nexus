(ns nexus.server
  (:require [reitit.ring :as ring]
            [clojure.data.json :as json]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [nexus.authenticator :as auth]
            [nexus.datastore :as store]
            [slingshot.slingshot :refer [try+]]
            [fudo-clojure.ip :as ip]
            [fudo-clojure.common :refer [current-epoch-timestamp parse-epoch-timestamp]]))

(defn- set-host-ipv4 [store]
  (fn [{:keys [body]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string body)]
       (when (not (ip/ipv4? ip))
         {:status 400
          :body (format "rejected: not a v4 IP: %s" body)})
       (store/set-host-ipv4 store domain host ip)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" body)})
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body (format "an unknown error has occurred: %s"
                      (.toString e))}))))

(defn- set-host-ipv6 [store]
  (fn [{:keys [body]
       {:keys [host domain]} :path-params}]
    (try+
     (let [ip (ip/from-string body)]
       (when (not (ip/ipv6? ip))
         {:status 400
          :body (format "rejected: not a v6 IP: %s" body)})
       (store/set-host-ipv4 store domain host ip)
       {:status 200 :body (str ip)})
     (catch IllegalArgumentException _
       {:status 400
        :body (format "rejected: failed to parse IP: %s" body)})
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body (format "an unknown error has occurred: %s"
                      (.toString e))}))))

(defn- valid-sshfp? [sshfp]
  (not (nil? (re-matches #"^[12346] [12] [0-9a-fA-F ]{20,256}$" sshfp))))

(defn- set-host-sshfps [store]
  (fn [{:keys [body]
       {:keys [host domain]} :path-params}]
    (try+
     (if (not (every? valid-sshfp? body))
       {:status 400 :body "rejected: invalid sshfp"}
       (do (store/set-host-sshfps store domain host body)
           {:status 200 :body body}))
     (catch Exception e
       ;; FIXME: don't spill the beans
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-ipv4 [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv4 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv4 for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-ipv6 [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [ip (store/get-host-ipv6 store domain host)]
       (if ip
         {:status 200 :body (str ip)}
         {:status 404 :body (format "IPv6 for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- get-host-sshfps [store]
  (fn [{{:keys [host domain]} :path-params}]
    (try+
     (let [sshfps (store/get-host-sshfps store domain host)]
       (if sshfps
         {:status 200 :body sshfps}
         {:status 404 :body (format "SSHFP for %s.%s not found." host domain)}))
     (catch Exception e
       {:status 500
        :body {:error (format "an unknown error has occurred: %s"
                              (.toString e))}}))))

(defn- decode-body [handler _]
  (fn [req] (handler (update req :body json/read-str))))

(defn- encode-body [handler _]
  (fn [req]
    (let [resp (handler req)]
      (assoc resp :body (json/write-str (:body resp))))))

(defn- build-request-string [& {:keys [body method uri timestamp]}]
  (str (-> method (name) (str/upper-case))
       uri
       timestamp
       body))

(defn- authenticate-request [authenticator
                             {:keys [body request-method uri]
                              {:keys [access-signature access-timestamp host]} :headers}]
  (let [req-str (build-request-string :body body
                                      :method request-method
                                      :uri uri
                                      :timestamp access-timestamp)]
    (auth/validate-signature authenticator host req-str access-signature)))

(defn- make-host-signature-authenticator [authenticator]
  (fn [handler _]
    (fn [req]
      (if (authenticate-request authenticator req)
        (handler req)
        { :status 401 :body "rejected: request signature invalid" }))))

(defn- make-timing-validator [max-diff]
  (fn [handler _]
    (fn [{{:keys [access-timestamp]} :headers
         :as req}]
      (let [timestamp (parse-epoch-timestamp access-timestamp)
            current-timestamp (current-epoch-timestamp)
            time-diff (abs (- timestamp current-timestamp))]
        (if (> time-diff max-diff)
          { :status 412 :body "rejected: request timestamp out of date" }
          (handler req))))))

(defn create-app [{:keys [authenticator data-store max-delay]}]
  (ring/ring-handler
   (ring/router ["/api" {:middleware [decode-body encode-body (make-timing-validator max-delay)]}
                 ["/:domain"
                  ["/:host" {:middleware [(make-host-signature-authenticator authenticator)]}
                   ["/ipv4" {:put {:handler (set-host-ipv4 data-store)}
                             :get {:handler (get-host-ipv4 data-store)}}]
                   ["/ipv6" {:put {:handler (set-host-ipv6 data-store)}
                             :get {:handler (get-host-ipv6 data-store)}}]
                   ["/sshfps" {:put {:handler (set-host-sshfps data-store)}
                               :get {:handler (get-host-sshfps data-store)}}]]]]
                (ring/create-default-handler))))
