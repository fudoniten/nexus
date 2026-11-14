(ns nexus.pdns-datastore-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [nexus.datastore :as ds]
            [nexus.pdns-datastore :as pdns]
            [slingshot.slingshot :refer [try+]])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

(deftest host-rrsets-are-full-state
  (testing "present types are replaced, absent types deleted"
    (is (= [{:name "h1.test.com." :type "A" :ttl 300 :changetype "REPLACE"
             :records [{:content "192.0.2.1" :disabled false}]}
            {:name "h1.test.com." :type "AAAA" :changetype "DELETE"}
            {:name "h1.test.com." :type "SSHFP" :changetype "DELETE"}]
           (pdns/host-rrsets "h1" "test.com" 300 {:ipv4 "192.0.2.1"}))))
  (testing "names are lowercased and duplicate records dropped"
    (is (= [{:content "1 2 ab" :disabled false}]
           (-> (pdns/host-rrsets "H1" "Test.COM" 300 {:ipv4 "192.0.2.1"
                                                      :sshfps ["1 2 ab" "1 2 ab"]})
               (nth 2)
               :records)))
    (is (= "h1.test.com." (-> (pdns/host-rrsets "H1" "Test.COM" 300 {}) first :name)))))

(defn- with-fake-pdns
  "Run f against an API client pointed at a local HTTP server that answers
  every request with status and body, recording each request."
  [status body f]
  (let [requests (atom [])
        server   (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^HttpExchange ex ex]
                          (swap! requests conj {:method (.getRequestMethod ex)
                                                :path   (.getPath (.getRequestURI ex))
                                                :key    (.getFirst (.getRequestHeaders ex) "X-API-Key")
                                                :body   (slurp (.getRequestBody ex))})
                          (let [bytes (.getBytes ^String body "UTF-8")]
                            (.sendResponseHeaders ex status (if (empty? bytes) -1 (count bytes)))
                            (with-open [out (.getResponseBody ex)]
                              (.write out bytes)))))))
    (.start server)
    (try
      (f (pdns/make-api {:base-url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
                         :api-key  "k"})
         requests)
      (finally (.stop server 0)))))

(defn- outcome [api]
  (try+
   (pdns/patch-rrsets! api "test.com" [(pdns/delete-rrset "h1.test.com." "A")])
   :ok
   (catch ds/store-error? e (::ds/error e))))

(deftest patch-rrsets-maps-api-responses
  (testing "a 204 succeeds, and the request is a keyed PATCH of the zone"
    (with-fake-pdns 204 ""
      (fn [api requests]
        (is (= :ok (outcome api)))
        (let [{:keys [method path key body]} (first @requests)]
          (is (= "PATCH" method))
          (is (= "/api/v1/servers/localhost/zones/test.com." path))
          (is (= "k" key))
          (is (= "DELETE" (-> (json/read-str body :key-fn keyword) :rrsets first :changetype)))))))
  (testing "a CNAME conflict is :conflict"
    (with-fake-pdns 422 "{\"error\": \"RRset h1.test.com. IN A: Conflicts with pre-existing RRset\"}"
      (fn [api _] (is (= :conflict (outcome api))))))
  (testing "another 422 is :invalid"
    (with-fake-pdns 422 "{\"error\": \"RRset h1.other.com. IN A: Name is out of zone\"}"
      (fn [api _] (is (= :invalid (outcome api))))))
  (testing "an unknown zone is :no-such-zone"
    (with-fake-pdns 404 "Not Found"
      (fn [api _] (is (= :no-such-zone (outcome api))))))
  (testing "anything else is :api-error, which the server answers with 502"
    (with-fake-pdns 500 "boom"
      (fn [api _]
        (is (= :api-error (outcome api)))
        (is (= 502 (:status (ds/error-response {::ds/error :api-error ::ds/message "x"}))))))))
