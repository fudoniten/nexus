(ns nexus.sql-datastore
  "Read-only access to the PowerDNS database. Every write goes through the
  PowerDNS API (nexus.pdns-datastore); these are the plain SELECTs behind the
  server's GET endpoints, for which the API has no cheaper equivalent."
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where]]
            [next.jdbc :as jdbc]
            [clojure.string :as str]))

(defrecord SqlReader [verbose datasource])

(defn fetch!
  "Fetch results for the given SQL query"
  [{:keys [verbose datasource]} query]
  (let [formatted (sql/format query)]
    (when verbose
      (println (str "fetching: " formatted)))
    (jdbc/execute! datasource formatted)))

(defn- domain-id-sql [domain]
  (-> (select :id)
      (from   :domains)
      (where  [:= :name (str/lower-case domain)])))

(defn get-domain-id
  "The database id of a domain, or nil if this server has no such zone."
  [store domain]
  (when-not (str/blank? domain)
    (some->> (domain-id-sql domain)
             (fetch! store)
             (first)
             :domains/id)))

(defn- record-contents
  "The contents of host's records of record-type in domain."
  [store domain host record-type]
  (->> (-> (select :content)
           (from :records)
           (where [:= :name (str/lower-case (format "%s.%s" (name host) domain))]
                  [:= :type record-type]
                  [:= :domain_id (domain-id-sql domain)]))
       (fetch! store)
       (map :records/content)))

(defn get-host-ipv4 [store domain host]
  (first (record-contents store domain host "A")))

(defn get-host-ipv6 [store domain host]
  (first (record-contents store domain host "AAAA")))

(defn get-host-sshfps [store domain host]
  (seq (record-contents store domain host "SSHFP")))

(defn get-challenge-records
  "The ids of the active ACME challenges in a domain."
  [store domain]
  (when-let [domain-id (get-domain-id store domain)]
    (->> (-> (select :challenge_id)
             (from :challenges)
             (where [:= :domain_id domain-id]
                    [:= :active true]))
         (fetch! store)
         (map :challenges/challenge_id))))

(defn get-zone-serial
  "The SOA serial of a domain, or nil."
  [store domain]
  (some->> (-> (select :content)
               (from :records)
               (where [:= :type "SOA"]
                      [:= :domain_id (domain-id-sql domain)]))
           (fetch! store)
           (first)
           :records/content
           (re-find #"^\S+\s+\S+\s+(\d+)")
           (second)
           (parse-long)))

(defn list-all-records
  "Every record, with the name of its domain."
  [store]
  (->> (-> (select :records.id
                   :domains.name
                   :records.name
                   :records.type
                   :records.content
                   :records.ttl
                   :records.prio
                   :records.disabled)
           (from :records)
           (join :domains [:= :records.domain_id :domains.id]))
       (fetch! store)
       (map (fn [record]
              {:id       (:records/id record)
               :domain   (:domains/name record)
               :name     (:records/name record)
               :type     (:records/type record)
               :content  (:records/content record)
               :ttl      (:records/ttl record)
               :prio     (:records/prio record)
               :disabled (:records/disabled record)}))))

(defn connect [{:keys [database-user database-password-file database-host database-port database verbose]
                :or {database-port 5432
                     verbose       false}}]
  (when verbose (println (str "initializing sql reader: " database-host ":" database-port "/" database)))
  (->SqlReader verbose
               (jdbc/get-datasource {:dbtype   "postgresql"
                                     :dbname   database
                                     :user     database-user
                                     :password (-> database-password-file
                                                   (slurp)
                                                   (str/trim))
                                     :host     database-host
                                     :port     database-port})))
