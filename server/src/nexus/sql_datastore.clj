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

(defn- host-has-record-sql [domain host record-type]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :id)
        (from :records)
        (join :domains [:= :records.domain_id :domains.id])
        (where [:= :records.name fqdn]
               [:= :domains.name domain]
               [:= :records.type record-type]))))

(defn- host-has-record? [store domain host record-type]
  (->> (host-has-record-sql domain host record-type)
       (sql/format)
       (exec! store)
       (seq)))

(defn- domain-id-sql [domain]
  (-> (select :id)
      (from   :domains)
      (where  [:= :name domain])))

(defn- host-has-ipv4? [store domain host]
  (host-has-record? store domain host "A"))

(defn- host-has-ipv6? [store domain host]
  (host-has-record? store domain host "AAAA"))

(defn- insert-records-sql [domain host record-type contents]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (insert-into :records)
        (values (map (fn [content]
                       {:name      fqdn
                        :type      record-type
                        :content   content
                        :domain_id (domain-id-sql domain)})
                     contents)))))

(defn- insert-host-ipv4-sql [domain host ip]
  (insert-records-sql domain host "A" [(str ip)]))

(defn- insert-host-ipv6-sql [domain host ip]
  (insert-records-sql domain host "AAAA" [(str ip)]))

(defn- insert-host-sshfps-sql [domain host sshfps]
  (insert-records-sql domain host "SSHFP" sshfps))

(defn- insert-host-ipv4 [store domain host ip]
  (exec! store (insert-host-ipv4-sql domain host (:domain store) ip)))

(defn- insert-host-ipv6 [store domain host ip]
  (exec! store (insert-host-ipv6-sql domain host (:domain store) ip)))

(defn- update-record-sql [domain host record-type content]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (update :records)
        (set {:content content})
        (where [:= :name      fqdn]
               [:= :type      record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- update-host-ipv4-sql [domain host ip]
  (update-record-sql domain host "A" (str ip)))

(defn- update-host-ipv6-sql [domain host ip]
  (update-record-sql domain host "AAAA" (str ip)))

(defn- update-host-ipv4 [store domain host ip]
  (exec! store (update-host-ipv4-sql domain host ip)))

(defn- update-host-ipv6 [store domain host ip]
  (exec! store (update-host-ipv6-sql domain host ip)))

(defn- delete-host-sshfps-sql [domain host]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (delete-from :records)
        (where [:= :name      fqdn]
               [:= :type      "SSHFP"]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- set-host-ipv4-impl [store domain host ip]
  (if (host-has-ipv4? store domain host)
    (update-host-ipv4 store domain host ip)
    (insert-host-ipv4 store domain host ip)))

(defn- set-host-ipv6-impl [store domain host ip]
  (if (host-has-ipv6? store domain host)
    (update-host-ipv6 store domain host ip)
    (insert-host-ipv6 store domain host ip)))

(defn- set-host-sshpfs-impl [store domain host sshfps]
  (exec! store
         (delete-host-sshfps-sql host)
         (insert-host-sshfps-sql domain host sshfps)))

(defn- get-record-contents-sql [record-type domain host]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :content)
        (from :records)
        (where [:= :name fqdn]
               [:= :type record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- get-host-ipv4-sql [domain host]
  (get-record-contents-sql "A" domain host))

(defn- get-host-ipv6-sql [domain host]
  (get-record-contents-sql "AAAA" domain host))

(defn- get-host-sshfps-sql [domain host]
  (get-record-contents-sql "SSHFP" domain host))

(defn- get-host-ipv4-impl [store domain host]
  (first (exec! store (get-host-ipv4-sql domain host))))

(defn- get-host-ipv6-impl [store domain host]
  (first (exec! store (get-host-ipv6-sql domain host))))

(defn- get-host-sshfps-impl [store domain host]
  (exec! store (get-host-sshfps-sql domain host)))

(defrecord SqlDataStore [datasource]

  datastore/IDataStore

  (set-host-ipv4 [_ domain host ip]
    (set-host-ipv4-impl datasource domain host ip))
  (set-host-ipv6 [_ domain host ip]
    (set-host-ipv6-impl datasource domain host ip))
  (set-host-sshfps [_ domain host sshfps]
    (set-host-sshpfs-impl datasource domain host sshfps))

  (get-host-ipv4 [_ domain host]
    (get-host-ipv4-impl datasource domain host))
  (get-host-ipv6 [_ domain host]
    (get-host-ipv6-impl datasource domain host))
  (get-host-sshfps [_ domain host]
    (get-host-sshfps-impl datasource domain host)))

(defn connect [{:keys [database-user database-password-file database-host database-port database]
                :or {database-port 5432}}]
  (SqlDataStore. (jdbc/get-datasource {:dbtype   "postgresql"
                                       :dbname   database
                                       :user     database-user
                                       :password (-> database-password-file
                                                     (slurp)
                                                     (str/trim))
                                       :host     database-host
                                       :port     database-port})))
