(ns nexus.host-alias-map-test
  (:require [clojure.test :refer [deftest is testing]]
            [nexus.host-alias-map :as mapper]
            [clojure.java.io :as io]
            [clojure.data.json :as json]))

(deftest test-make-domain-fqdns
  (testing "Creating FQDNs from domain and aliases"
    (let [domain-alias {:domain "example.com" :aliases ["www" "mail" "ftp"]}
          fqdns (mapper/make-domain-fqdns domain-alias)]
      (is (= ["www.example.com" "mail.example.com" "ftp.example.com"] fqdns)))))

(deftest test-make-host-pairs
  (testing "Creating host pairs from alias configuration"
    (let [config [:canonical-host [{:domain "example.com" :aliases ["www" "mail"]}
                                   {:domain "other.com" :aliases ["web"]}]]
          pairs (mapper/make-host-pairs config)]
      (is (= [["www.example.com" :canonical-host]
              ["mail.example.com" :canonical-host]
              ["web.other.com" :canonical-host]]
             pairs)))))

(deftest test-host-alias-map-get-host
  (testing "Getting canonical host from alias"
    (let [alias-map {"www.example.com" :web-server
                     "mail.example.com" :mail-server}
          mapper (mapper/->HostAliasMap alias-map)]
      (is (= :web-server (mapper/get-host mapper "www" "example.com")))
      (is (= :mail-server (mapper/get-host mapper "mail" "example.com")))))
  
  (testing "Getting host when no alias exists returns original host"
    (let [mapper (mapper/->HostAliasMap {})]
      (is (= :some-host (mapper/get-host mapper "some-host" "example.com"))))))

(deftest test-make-mapper-from-file
  (testing "Creating mapper from JSON file"
    (let [temp-file (java.io.File/createTempFile "test-aliases" ".json")
          config {:web-server [{:domain "example.com" :aliases ["www" "web"]}
                               {:domain "test.com" :aliases ["www"]}]
                  :mail-server [{:domain "example.com" :aliases ["mail" "smtp"]}]}]
      (try
        ;; Write test config to file
        (with-open [writer (io/writer temp-file)]
          (json/write config writer))
        
        ;; Load mapper from file
        (let [mapper (mapper/make-mapper (.getPath temp-file))]
          (is (instance? nexus.host_alias_map.HostAliasMap mapper))
          (is (= :web-server (mapper/get-host mapper "www" "example.com")))
          (is (= :web-server (mapper/get-host mapper "web" "example.com")))
          (is (= :web-server (mapper/get-host mapper "www" "test.com")))
          (is (= :mail-server (mapper/get-host mapper "mail" "example.com")))
          (is (= :mail-server (mapper/get-host mapper "smtp" "example.com")))
          ;; Non-aliased host returns itself
          (is (= :other (mapper/get-host mapper "other" "example.com"))))
        (finally
          (.delete temp-file))))))

(deftest test-make-mapper-empty
  (testing "Creating empty mapper when no file provided"
    (let [mapper (mapper/make-mapper nil)]
      (is (instance? nexus.host_alias_map.HostAliasMap mapper))
      (is (= :any-host (mapper/get-host mapper "any-host" "any-domain"))))))
