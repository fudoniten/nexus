(ns nexus.server-test
  (:require [nexus.server :as srv]
            [nexus.datastore :as ds]
            [nexus.host-alias-map :as mapper]
            [clojure.test :as t :refer [deftest is testing run-tests]]
            [ring.mock.request :as ring]
            [clojure.string :as str]
            [fudo-clojure.common :refer [current-epoch-timestamp base64-encode-string]]
            [nexus.crypto :as crypto]
            [nexus.authenticator :as auth]
            [clojure.data.json :as json])
  (:import javax.crypto.Mac))

(defn- make-datastore [data]
  (reify ds/IDataStore
    (set-host-ipv4   [_ _ _ ip]     ip)
    (set-host-ipv6   [_ _ _ ip]     ip)
    (set-host-sshfps [_ _ _ sshfps] sshfps)

    (get-host-ipv4   [_ domain host] (-> data (get domain) (get host) (get :ipv4)))
    (get-host-ipv6   [_ domain host] (-> data (get domain) (get host) (get :ipv6)))
    (get-host-sshfps [_ domain host] (-> data (get domain) (get host) (get :sshfps)))))

(defn- make-datastore-throwing [err]
  (reify ds/IDataStore
    (set-host-ipv4   [_ _ _ _] (throw err))
    (set-host-ipv6   [_ _ _ _] (throw err))
    (set-host-sshfps [_ _ _ _] (throw err))

    (get-host-ipv4   [_ _ _] (throw err))
    (get-host-ipv6   [_ _ _] (throw err))
    (get-host-sshfps [_ _ _] (throw err))))

(defn- gen-key []
  (-> ["HmacSHA1" "HmacSHA256"]
      (rand-nth)
      (crypto/generate-key)
      (crypto/encode-key)))

(defn- gen-sshfp []
  (let [hex-chars "0123456789ABCDEFabcdef"
        gen-len   (fn [len] (apply str
                                  (repeatedly len
                                              #(rand-nth hex-chars))))]
    (str/join " "
              [(str (rand-nth [1 2 3 4 6]))
               (str (rand-nth [1 2]))
               (gen-len (+ 20 (rand-int 40)))])))

(defn- sign [key-str msg]
  (let [hmac-key (crypto/decode-key key-str)
        hmac (doto (Mac/getInstance (.getAlgorithm hmac-key))
               (.init hmac-key))]
    (-> (.doFinal hmac (.getBytes msg))
        (base64-encode-string))))

(defn- read-body [{:keys [body]}]
  (if body
    (let [body-str (slurp body)]
      (.reset body)
      body-str)
    ""))

(defn- sign-request [req key-str]
  (let [timestamp (-> req
                      (get-in [:headers "access-timestamp"])
                      (or (str (current-epoch-timestamp))))
        req-str   (str (-> req :request-method (name) (str/upper-case))
                       (-> req :uri)
                       timestamp
                       (-> req (read-body)))
        sig       (sign key-str req-str)]
    (-> req
        (ring/header :access-timestamp timestamp)
        (ring/header :access-signature sig))))

(deftest get-failures
  (let [datastore (make-datastore {})
        host-keys {:host0 (gen-key)
                   :host1 (gen-key)
                   :host2 (gen-key)}
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        auther (auth/make-authenticator host-keys false)
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "missing-timestamp"
      (is (= (-> (app (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4"))
                 :status)
             406)))

    (testing "old-timestamp"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4")
                          (ring/header  :access-timestamp (str (- (current-epoch-timestamp) 120)))
                          (sign-request (:host0 host-keys))))
                 :status)
             412)))

    (testing "missing-signature"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4")
                          (ring/header  :access-timestamp (current-epoch-timestamp))))
                 :status)
             406)))

    (testing "invalid-signature"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4")
                          (sign-request (:host1 host-keys))))
                 :status)
             401)))

    (testing "missing-host-key"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host-missing/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))

    (testing "missing-domain"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/oops.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))

    (testing "missing-host"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/oops.com/host/host-missing/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))

    (testing "bad-url"
      (is (= (-> (app (-> (ring/request :get "/nothing/oops.com/host-missing/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))))

(deftest get-successes
  (let [ipv4 "1.1.1.1"
        ipv6 "1::1"
        sshfps (repeatedly (+ 1 (rand-int 4)) #(gen-sshfp))
        datastore (make-datastore
                   {"test.com"
                    {"host0" {:ipv4 ipv4
                              :ipv6 ipv6
                              :sshfps sshfps}}})
        host-keys {:host0 (gen-key)
                   :host1 (gen-key)
                   :host2 (gen-key)}
        auther (auth/make-authenticator host-keys true)
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "get-ipv4-status"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "get-ipv4"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :body)
             (json/write-str ipv4))))

    (testing "get-ipv6-status"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv6")
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "get-ipv6"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/ipv6")
                          (sign-request (:host0 host-keys))))
                 :body)
             (json/write-str ipv6))))

    (testing "get-sshfps"
      (is (= (-> (app (-> (ring/request :get "/api/v2/domain/test.com/host/host0/sshfps")
                          (sign-request (:host0 host-keys))))
                 :body)
             (json/write-str sshfps))))))

(deftest set-successes
  (let [datastore (make-datastore {})
        host-keys {:host0 (gen-key)
                   :host1 (gen-key)
                   :host2 (gen-key)}
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        auther (auth/make-authenticator host-keys false)
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "set-ipv4-status"
      (is (= (-> (app (-> (ring/request :put "/api/v2/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "set-ipv6-status"
      (is (= (-> (app (-> (ring/request :put "/api/v2/domain/test.com/host/host0/ipv6")
                          (ring/body (json/write-str "1::1"))
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "set-sshfps-status"
      (is (= (-> (app (-> (ring/request :put "/api/v2/domain/test.com/host/host0/sshfps")
                          (ring/body (json/write-str (repeatedly (+ 1 (rand-int 4))
                                                                 #(gen-sshfp))))
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))))

(deftest set-failures
  (let [datastore (make-datastore {})
        host-keys {:host0 (gen-key)
                   :host1 (gen-key)
                   :host2 (gen-key)}
        auther (auth/make-authenticator host-keys false)
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "bad-signature"
      (is (= (-> (app (-> (ring/request :put "/api/v2/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host0 host-keys))
                          (ring/header :access-signature "ouidnaouidnaouidnadouindaoui")))
                 :status)
             401)))

    (testing "wrong-host"
      (is (= (-> (app (-> (ring/request :put "/api/v2/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host1 host-keys))))
                 :status)
              401)))))
