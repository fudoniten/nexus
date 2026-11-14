(ns nexus.server-test
  (:require [nexus.server :as srv]
            [nexus.datastore :as ds]
            [nexus.host-alias-map :as mapper]
            [clojure.test :as t :refer [deftest is testing run-tests]]
            [ring.mock.request :as ring]
            [clojure.string :as str]
            [fudo-clojure.common :refer [current-epoch-timestamp]]
            [nexus.crypto :as crypto]
            [nexus.authenticator :as auth]
            [clojure.data.json :as json]
            [slingshot.slingshot :refer [throw+]])
 )

(defn- make-datastore [data]
  (reify ds/IDataStore
    (set-host-ipv4   [_ _ _ ip]     ip)
    (set-host-ipv6   [_ _ _ ip]     ip)
    (set-host-sshfps [_ _ _ sshfps] sshfps)

    (get-host-ipv4   [_ domain host] (-> data (get domain) (get host) (get :ipv4)))
    (get-host-ipv6   [_ domain host] (-> data (get domain) (get host) (get :ipv6)))
    (get-host-sshfps [_ domain host] (-> data (get domain) (get host) (get :sshfps)))))

(defn- make-mutable-datastore
  "Like make-datastore, but actually persists writes (in an atom), so a
  test can PUT and then GET the same host/domain in the same datastore and
  see its own write. make-datastore's set-* just echoes back what it was
  given -- it never touches its (closed-over, immutable) data -- which is
  fine for the existing tests (each only ever exercises one direction),
  but wrong for a test that checks read-your-writes."
  []
  (let [data (atom {})]
    (reify ds/IDataStore
      (set-host-ipv4   [_ domain host ip]     (swap! data assoc-in [domain host :ipv4] ip) ip)
      (set-host-ipv6   [_ domain host ip]     (swap! data assoc-in [domain host :ipv6] ip) ip)
      (set-host-sshfps [_ domain host sshfps] (swap! data assoc-in [domain host :sshfps] sshfps) sshfps)

      (get-host-ipv4   [_ domain host] (get-in @data [domain host :ipv4]))
      (get-host-ipv6   [_ domain host] (get-in @data [domain host :ipv6]))
      (get-host-sshfps [_ domain host] (get-in @data [domain host :sshfps])))))

(defn- make-datastore-throwing [err]
  (reify ds/IDataStore
    (set-host-ipv4   [_ _ _ _] (throw err))
    (set-host-ipv6   [_ _ _ _] (throw err))
    (set-host-sshfps [_ _ _ _] (throw err))

    (get-host-ipv4   [_ _ _] (throw err))
    (get-host-ipv6   [_ _ _] (throw err))
    (get-host-sshfps [_ _ _] (throw err))))

(def ^:private test-public-keys
  "The encoded public key for each private key gen-key hands out, so a test
  can name hosts by private key and still build the server's authenticator,
  which holds only public keys."
  (atom {}))

(defn- gen-key
  "A fresh Ed25519 private key, encoded."
  []
  (let [{:keys [public-key private-key]} (crypto/generate-keypair)
        encoded (crypto/encode-private-key private-key)]
    (swap! test-public-keys assoc encoded (crypto/encode-public-key public-key))
    encoded))

(defn- make-authenticator
  "The server's authenticator for a map of signer to encoded private key."
  [signer-keys verbose]
  (auth/make-pubkey-authenticator (update-vals signer-keys @test-public-keys) verbose))

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
  (crypto/sign-with-private-key (crypto/decode-private-key key-str) msg))

(defn- read-body [{:keys [body]}]
  (if body
    (let [body-str (slurp body)]
      (.reset body)
      body-str)
    ""))

(defn- sign-request-v3
  "Same as sign-request, but takes a decoded private key."
  [req private-key]
  (let [timestamp (-> req
                      (get-in [:headers "access-timestamp"])
                      (or (str (current-epoch-timestamp))))
        req-str   (str (-> req :request-method (name) (str/upper-case))
                       (-> req :uri)
                       timestamp
                       (-> req (read-body)))
        sig       (crypto/sign-with-private-key private-key req-str)]
    (-> req
        (ring/header :access-timestamp timestamp)
        (ring/header :access-signature sig))))

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
        auther (make-authenticator host-keys false)
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    ;; The signature check runs ahead of the timestamp check on host routes,
    ;; so an unsigned request is rejected there with 401 whether or not it
    ;; carries a timestamp. (406 is the challenge API's code for a missing
    ;; signature; these are host routes.)
    (testing "missing-signature-and-timestamp"
      (is (= (-> (app (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4"))
                 :status)
             401)))

    (testing "old-timestamp"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/header  :access-timestamp (str (- (current-epoch-timestamp) 120)))
                          (sign-request (:host0 host-keys))))
                 :status)
             412)))

    (testing "malformed-timestamp"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/header  :access-timestamp "yesterday")
                          (sign-request (:host0 host-keys))))
                 :status)
             400)))

    (testing "missing-signature"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/header  :access-timestamp (current-epoch-timestamp))))
                 :status)
             401)))

    (testing "invalid-signature"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (sign-request (:host1 host-keys))))
                 :status)
             401)))

    (testing "missing-host-key"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host-missing/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))

    (testing "missing-domain"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/oops.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             404)))

    (testing "missing-host"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/oops.com/host/host-missing/ipv4")
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
        auther (make-authenticator host-keys true)
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "get-ipv4-status"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    ;; get-host-ipv4 returns (str ip), and encode-body deliberately leaves an
    ;; already-string body alone, so the body arrives bare rather than
    ;; JSON-quoted. get-sshfps below returns a collection, which does get
    ;; JSON-encoded.
    (testing "get-ipv4"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                          (sign-request (:host0 host-keys))))
                 :body)
             ipv4)))

    (testing "get-ipv6-status"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv6")
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "get-ipv6"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv6")
                          (sign-request (:host0 host-keys))))
                 :body)
             ipv6)))

    (testing "get-sshfps"
      (is (= (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/sshfps")
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
        auther (make-authenticator host-keys false)
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "set-ipv4-status"
      (is (= (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "set-ipv6-status"
      (is (= (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv6")
                          (ring/body (json/write-str "1::1"))
                          (sign-request (:host0 host-keys))))
                 :status)
             200)))

    (testing "set-sshfps-status"
      (is (= (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/sshfps")
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
        auther (make-authenticator host-keys false)
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        app    (srv/create-app :host-authenticator auther
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "bad-signature"
      (is (= (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host0 host-keys))
                          (ring/header :access-signature "ouidnaouidnaouidnadouindaoui")))
                 :status)
             401)))

    (testing "wrong-host"
      (is (= (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv4")
                          (ring/body (json/write-str "1.1.1.1"))
                          (sign-request (:host1 host-keys))))
                 :status)
              401)))))

(deftest sshfp-accepted-for-alias
  (testing "an alias is a name the host publishes its own records under, so
            its SSHFPs are stored like any other host's"
    (let [batches (atom [])
          datastore (reify ds/IDataStore
                      (set-host-batch [_ _ host data]
                        (swap! batches conj {:host host :data data})
                        data))
          host-keys {:host0 (gen-key)}
          mapper (reify mapper/IHostAliasMap
                   (get-host [_ host domain]
                     (if (= (format "%s.%s" (name host) (name domain)) "alias0.test.com")
                       :host0
                       (keyword host))))
          app    (srv/create-app :host-authenticator (make-authenticator host-keys false)
                                 :data-store    datastore
                                 :host-mapper   mapper
                                 :max-delay     5)
          sshfp  (gen-sshfp)
          resp   (app (-> (ring/request :put "/api/v3/domain/test.com/host/alias0/batch")
                          (ring/body (json/write-str {:ipv4 "1.1.1.1" :sshfps [sshfp]}))
                          (sign-request (:host0 host-keys))))]
      (is (= 200 (:status resp)))
      (is (= [sshfp] (-> @batches first :data :sshfps))))))

(defn- refusing-datastore
  "A datastore whose every write is refused the way the PowerDNS API refuses
  a CNAME conflict."
  []
  (let [refuse (fn [& _] (throw+ {::ds/error :conflict
                                  ::ds/message "RRset host0.test.com. IN A: Conflicts with pre-existing RRset"}))]
    (reify ds/IDataStore
      (set-host-ipv4   [_ d h v] (refuse d h v))
      (set-host-ipv6   [_ d h v] (refuse d h v))
      (set-host-sshfps [_ d h v] (refuse d h v))
      (set-host-batch  [_ d h v] (refuse d h v)))))

(deftest cname-conflict-is-a-409
  (let [host-keys {:host0 (gen-key)}
        app (srv/create-app :host-authenticator (make-authenticator host-keys false)
                            :data-store  (refusing-datastore)
                            :host-mapper (reify mapper/IHostAliasMap
                                           (get-host [_ host _] (keyword host)))
                            :max-delay   5)]
    (testing "a write the DNS server refuses as a CNAME conflict is a 409 the
              client can see, not a 500"
      (let [resp (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/batch")
                          (ring/body (json/write-str {:ipv4 "1.1.1.1"}))
                          (sign-request (:host0 host-keys))))]
        (is (= 409 (:status resp)))
        (is (str/includes? (:body resp) "Conflicts with pre-existing RRset")))
      (is (= 409 (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv4")
                              (ring/body (json/write-str "1.1.1.1"))
                              (sign-request (:host0 host-keys))))
                     :status))))))

(deftest batch-without-addresses-is-refused
  (testing "a batch replaces the host's whole state, so one with no address
            would delete the host; it is refused instead"
    (let [written   (atom nil)
          host-keys {:host0 (gen-key)}
          app (srv/create-app :host-authenticator (make-authenticator host-keys false)
                              :data-store  (reify ds/IDataStore
                                             (set-host-batch [_ _ _ data] (reset! written data) data))
                              :host-mapper (reify mapper/IHostAliasMap
                                             (get-host [_ host _] (keyword host)))
                              :max-delay   5)
          resp (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/batch")
                        (ring/body (json/write-str {:sshfps [(gen-sshfp)]}))
                        (sign-request (:host0 host-keys))))]
      (is (= 400 (:status resp)))
      (is (nil? @written)))))

;; --- /api/v3: public-key (Ed25519) authenticated API ---

(defn- gen-keypair-pair
  "Returns [encoded-public-key private-key] for a fresh Ed25519 keypair."
  []
  (let [{:keys [public-key private-key]} (crypto/generate-keypair)]
    [(crypto/encode-public-key public-key) private-key]))

(deftest v3-get-and-set-successes
  (let [[pub0 priv0] (gen-keypair-pair)
        [pub1 priv1] (gen-keypair-pair)
        ;; A GET immediately follows a PUT in this test, so it needs a
        ;; datastore that actually persists writes -- make-datastore
        ;; doesn't (see make-mutable-datastore's docstring).
        datastore (make-mutable-datastore)
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        auther-v3 (auth/make-pubkey-authenticator {:host0 pub0 :host1 pub1} false)
        app    (srv/create-app :host-authenticator auther-v3
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "set-ipv4 via v3, signed with the Ed25519 private key, succeeds"
      (is (= 200 (-> (app (-> (ring/request :put "/api/v3/domain/test.com/host/host0/ipv4")
                             (ring/body (json/write-str "1.1.1.1"))
                             (sign-request-v3 priv0)))
                     :status))))

    (testing "get-ipv4 via v3 returns the value set via v3"
      ;; encode-body leaves an already-string body alone (see its
      ;; docstring) -- get-host-ipv4 returns (str ip), so the body here is
      ;; the bare string, not a JSON-quoted one.
      (is (= "1.1.1.1"
             (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                         (sign-request-v3 priv0)))
                 :body))))

    (testing "a v3 request signed with another host's private key is rejected"
      (is (= 401 (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                             (sign-request-v3 priv1)))
                     :status))))))

(deftest v3-missing-key-rejected
  (let [[_pub0 priv0] (gen-keypair-pair)
        datastore (make-datastore {})
        mapper (reify mapper/IHostAliasMap
                 (get-host [_ host _] (keyword host)))
        ;; note: host0 deliberately absent from the v3 key collection
        auther-v3 (auth/make-pubkey-authenticator {} false)
        app    (srv/create-app :host-authenticator auther-v3
                               :data-store    datastore
                               :host-mapper   mapper
                               :max-delay     5)]
    (testing "a v3 request for a host with no registered public key is rejected"
      (is (= 404 (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                             (sign-request-v3 priv0)))
                     :status))))))

;; --- /api/v3: public-key authenticated ACME challenge records ---
;;
;; Challenge clients (the cert-manager webhook) authenticate with their own
;; keypair, independently of the hosts that report their addresses. The two
;; halves of an API version are mounted separately, so these tests pin down
;; what exists in each combination.

(defn- make-challenge-datastore
  "A datastore that records challenge writes in an atom, so a test can assert
  a request actually reached the handler rather than only that it was
  authenticated."
  [recorded]
  (reify ds/IDataStore
    (get-challenge-records [_ domain] [{:domain domain}])
    (create-challenge-record [_ domain host challenge-id secret]
      (swap! recorded conj {:op :create :domain domain :host host
                            :challenge-id challenge-id :secret secret})
      challenge-id)
    (delete-challenge-record [_ domain challenge-id]
      (swap! recorded conj {:op :delete :domain domain :challenge-id challenge-id})
      challenge-id)))

(defn- challenge-app
  "Build an app wired only for challenge requests."
  [recorded & {:keys [challenge-authenticator]}]
  (srv/create-app :challenge-authenticator challenge-authenticator
                  :data-store  (make-challenge-datastore recorded)
                  :host-mapper (reify mapper/IHostAliasMap
                                 (get-host [_ host _] (keyword host)))
                  :max-delay   5))

(deftest v3-challenge-mounted-without-host-pubkeys
  (testing "challenge public keys alone bring up the challenge API"
    (let [[pub priv] (gen-keypair-pair)
          recorded   (atom [])
          app        (challenge-app recorded
                                    :challenge-authenticator
                                    (auth/make-pubkey-authenticator {:acme pub} false))
          challenge-id (str (random-uuid))]
      (is (= 200 (-> (app (-> (ring/request :put (str "/api/v3/domain/test.com/challenge/" challenge-id))
                              (ring/body (json/write-str {:host "_acme-challenge" :secret "s3cret"}))
                              (ring/header :service "acme")
                              (sign-request-v3 priv)))
                     :status))
          "an Ed25519-signed challenge request is accepted")
      (is (= [{:op :create :domain "test.com" :host "_acme-challenge"
               :challenge-id challenge-id :secret "s3cret"}]
             @recorded)
          "the request reached the handler with its payload intact")

      (is (= 401 (-> (app (-> (ring/request :put (str "/api/v3/domain/test.com/challenge/" (random-uuid)))
                              (ring/body (json/write-str {:host "_acme-challenge" :secret "s3cret"}))
                              (ring/header :service "acme")
                              (sign-request-v3 (second (gen-keypair-pair)))))
                     :status))
          "a challenge request signed with an unregistered key is rejected"))))

(deftest v3-challenge-delete
  (testing "a challenge record can be deleted over v3"
    (let [[pub priv] (gen-keypair-pair)
          recorded   (atom [])
          app        (challenge-app recorded
                                    :challenge-authenticator
                                    (auth/make-pubkey-authenticator {:acme pub} false))
          challenge-id (random-uuid)]
      (is (= 200 (-> (app (-> (ring/request :delete (str "/api/v3/domain/test.com/challenge/" challenge-id))
                              (ring/header :service "acme")
                              (sign-request-v3 priv)))
                     :status)))
      (is (= [{:op :delete :domain "test.com" :challenge-id challenge-id}] @recorded)))))

(deftest v3-host-routes-absent-without-host-pubkeys
  (testing "with only challenge public keys configured, the v3 host API is not
            mounted at all -- it 404s rather than reaching a nil authenticator"
    (let [[pub _priv] (gen-keypair-pair)
          [_pub2 priv2] (gen-keypair-pair)
          app (challenge-app (atom [])
                             :challenge-authenticator
                             (auth/make-pubkey-authenticator {:acme pub} false))]
      (is (= 404 (-> (app (-> (ring/request :get "/api/v3/domain/test.com/host/host0/ipv4")
                              (sign-request-v3 priv2)))
                     :status))))))

(deftest v3-challenge-routes-absent-without-challenge-pubkeys
  (testing "with only host public keys configured, the v3 challenge API is not
            mounted -- previously it was mounted around a nil authenticator and
            failed with a 500 on the first request"
    (let [[pub priv] (gen-keypair-pair)
          datastore  (make-datastore {})
          mapper     (reify mapper/IHostAliasMap
                       (get-host [_ host _] (keyword host)))
          app        (srv/create-app :host-authenticator
                                     (auth/make-pubkey-authenticator {:host0 pub} false)
                                     :data-store  datastore
                                     :host-mapper mapper
                                     :max-delay   5)]
      (is (= 404 (-> (app (-> (ring/request :put (str "/api/v3/domain/test.com/challenge/" (random-uuid)))
                              (ring/body (json/write-str {:host "_acme-challenge" :secret "s"}))
                              (ring/header :service "acme")
                              (sign-request-v3 priv)))
                     :status))))))

(deftest create-app-rejects-empty-authenticator-set
  (testing "an app with no authenticators at all would expose no API, and is a
            misconfiguration worth failing loudly on rather than serving 404s"
    (is (thrown? clojure.lang.ExceptionInfo
                 (srv/create-app :data-store  (make-datastore {})
                                 :host-mapper (reify mapper/IHostAliasMap
                                                (get-host [_ host _] (keyword host))))))))
