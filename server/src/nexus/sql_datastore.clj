(ns nexus.sql-datastore
  (:refer-clojure :exclude [update set delete])
  (:require [honey.sql :as sql]
            [honey.sql.helpers :refer [select from join where insert-into update values set delete-from]]
            [next.jdbc :as jdbc]
            [nexus.datastore :as datastore]
            [clojure.string :as str]))

(defn- exec! [store & sqls]
  (let [ds (:datasource store)]
    (jdbc/with-transaction [tx (jdbc/get-connection ds)]
      (doseq [sql sqls]
        (jdbc/execute! tx (sql/format sql))))))

(defn- host-has-record-sql [host domain record-type]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :id)
        (from :records)
        (join :domains [:= :records.domain_id :domains.id])
        (where [:= :records.name fqdn]
               [:= :domains.name domain]
               [:= :records.type record-type]))))

(defn- host-has-record? [store host record-type]
  (let [domain (:domain store)]
    (->> (host-has-record-sql host domain record-type)
         (sql/format)
         (exec! store)
         (seq))))

(defn- domain-id-sql [domain]
  (-> (select :id)
      (from   :domains)
      (where  [:= :name domain])))

(defn- host-has-ipv4? [store host]
  (host-has-record? store host "A"))

(defn- host-has-ipv6? [store host]
  (host-has-record? store host "AAAA"))

(defn- insert-records-sql [host domain record-type contents]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (insert-into :records)
        (values (map (fn [content]
                       {:name      fqdn
                        :type      record-type
                        :content   content
                        :domain_id (domain-id-sql domain)})
                     contents)))))

(defn- insert-host-ipv4-sql [host domain ip]
  (insert-records-sql host domain "A" [(str ip)]))

(defn- insert-host-ipv6-sql [host domain ip]
  (insert-records-sql host domain "AAAA" [(str ip)]))

(defn- insert-host-sshfps-sql [host domain sshfps]
  (insert-records-sql host domain "SSHFP" sshfps))

(defn- insert-host-ipv4 [store host ip]
  (exec! store (insert-host-ipv4-sql host (:domain store) ip)))

(defn- insert-host-ipv6 [store host ip]
  (exec! store (insert-host-ipv6-sql host (:domain store) ip)))

(defn- update-record-sql [host domain record-type content]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (update :records)
        (set {:content content})
        (where [:= :name      fqdn]
               [:= :type      record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- update-host-ipv4-sql [host domain ip]
  (update-record-sql host domain "A" (str ip)))

(defn- update-host-ipv6-sql [host domain ip]
  (update-record-sql host domain "AAAA" (str ip)))

(defn- update-host-ipv4 [store host ip]
  (exec! store (update-host-ipv4-sql host (:domain store) ip)))

(defn- update-host-ipv6 [store host ip]
  (exec! store (update-host-ipv6-sql host (:domain store) ip)))

(defn- delete-host-sshfps-sql [host domain]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (delete-from :records)
        (where [:= :name      fqdn]
               [:= :type      "SSHFP"]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- set-host-ipv4-impl [store host ip]
  (if (host-has-ipv4? store host)
    (update-host-ipv4 store host ip)
    (insert-host-ipv4 store host ip)))

(defn- set-host-ipv6-impl [store host ip]
  (if (host-has-ipv6? store host)
    (update-host-ipv6 store host ip)
    (insert-host-ipv6 store host ip)))

(defn- set-host-sshpfs-impl [store host sshfps]
  (exec! store
         (delete-host-sshfps-sql host (:domain store))
         (insert-host-sshfps-sql host (:domain store) sshfps)))

(defn- get-record-contents-sql [record-type host domain]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :content)
        (from :records)
        (where [:= :name fqdn]
               [:= :type record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- get-host-ipv4-sql [host domain]
  (get-record-contents-sql "A" host domain))

(defn- get-host-ipv6-sql [host domain]
  (get-record-contents-sql "AAAA" host domain))

(defn- get-host-sshfps-sql [host domain]
  (get-record-contents-sql "SSHFP" host domain))

(defn- get-host-ipv4-impl [store host]
  (first (exec! store (get-host-ipv4-sql host (:domain store)))))

(defn- get-host-ipv6-impl [store host]
  (first (exec! store (get-host-ipv6-sql host (:domain store)))))

(defn- get-host-sshfps-impl [store host]
  (exec! store (get-host-sshfps-sql host (:domain store))))

(defrecord SqlDataStore [domain datasource]

  datastore/IDataStore

  (set-host-ipv4 [_ host ip]
    (set-host-ipv4-impl datasource host ip))
  (set-host-ipv6 [_ host ip]
    (set-host-ipv6-impl datasource host ip))
  (set-host-sshfps [_ host sshfps]
    (set-host-sshpfs-impl datasource host sshfps))

  (get-host-ipv4 [_ host]
    (get-host-ipv4-impl datasource host))
  (get-host-ipv6 [_ host]
    (get-host-ipv6-impl datasource host))
  (get-host-sshfps [_ host]
    (get-host-sshfps-impl datasource host)))

(defn connect [{:keys [domain database-user database-password-file database-host database-port database]
                :or {database-port 5432}}]
  (SqlDataStore. domain
                 (jdbc/get-datasource {:dbtype   "postgresql"
                                       :dbname   database
                                       :user     database-user
                                       :password (-> database-password-file
                                                     (slurp)
                                                     (str/trim))
                                       :host     database-host
                                       :port     database-port})))
