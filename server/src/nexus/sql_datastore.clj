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

(defn- host-has-record-sql [{:keys [domain host record-type]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :id)
        (from :records)
        (join :domains [:= :records.domain_id :domains.id])
        (where [:= :records.name fqdn]
               [:= :domains.name domain]
               [:= :records.type record-type]))))

(defn- host-has-record? [store params]
  (->> (host-has-record-sql params)
       (sql/format)
       (exec! store)
       (seq)))

(defn- domain-id-sql [domain]
  (-> (select :id)
      (from   :domains)
      (where  [:= :name domain])))

(defn- host-has-ipv4? [store params]
  (host-has-record? store (assoc params :record-type "A")))

(defn- host-has-ipv6? [store params]
  (host-has-record? store (assoc params :record-type "AAAA")))

(defn- insert-records-sql [{:keys [host domain record-type contents]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (insert-into :records)
        (values (map (fn [content]
                       {:name      fqdn
                        :type      record-type
                        :content   content
                        :domain_id (domain-id-sql domain)})
                     contents)))))

(defn- insert-host-ipv4-sql [params ip]
  (-> params
      (assoc :record-type "A")
      (assoc :contents [(str ip)])
      (insert-records-sql)))

(defn- insert-host-ipv6-sql [params ip]
  (-> params
      (assoc :record-type "AAAA")
      (assoc :contents [(str ip)])
      (insert-records-sql)))

(defn- insert-host-sshfps-sql [params sshfps]
  (-> params
      (assoc :record-type "SSHFP")
      (assoc :contents sshfps)
      (insert-records-sql)))

(defn- insert-host-ipv4 [store params ip]
  (exec! store (insert-host-ipv4-sql params ip)))

(defn- insert-host-ipv6 [store params ip]
  (exec! store (insert-host-ipv6-sql params ip)))

(defn- update-record-sql [{:keys [domain host record-type content]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (update :records)
        (set {:content content})
        (where [:= :name      fqdn]
               [:= :type      record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- update-record-on-diff-sql [{:keys [content] :as params}]
  (-> (update-record-sql params)
      (where [:<> :content content])))

(defn- update-host-ipv4-sql [params ip]
  (-> params
      (assoc :record-type "A")
      (assoc :content (str ip))
      (update-record-on-diff-sql)))

(defn- update-host-ipv6-sql [params ip]
  (-> params
      (assoc :record-type "AAAA")
      (assoc :content (str ip))
      (update-record-on-diff-sql)))

(defn- update-host-ipv4 [store params ip]
  (exec! store (update-host-ipv4-sql params ip)))

(defn- update-host-ipv6 [store params ip]
  (exec! store (update-host-ipv6-sql params ip)))

(defn- delete-host-sshfps-sql [{:keys [host domain]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (delete-from :records)
        (where [:= :name      fqdn]
               [:= :type      "SSHFP"]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- set-host-ipv4-impl [store params ip]
  (if (host-has-ipv4? store params)
    (update-host-ipv4 store params ip)
    (insert-host-ipv4 store params ip)))

(defn- set-host-ipv6-impl [store params ip]
  (if (host-has-ipv6? store params)
    (update-host-ipv6 store params ip)
    (insert-host-ipv6 store params ip)))

(defn- set-host-sshpfs-impl [store params sshfps]
  (exec! store
         (delete-host-sshfps-sql params)
         (insert-host-sshfps-sql params sshfps)))

(defn- get-record-contents-sql [{:keys [record-type domain host]}]
  (let [fqdn (format "%s.%s" host domain)]
    (-> (select :content)
        (from :records)
        (where [:= :name fqdn]
               [:= :type record-type]
               [:= :domain_id (domain-id-sql domain)]))))

(defn- get-host-ipv4-sql [params]
  (get-record-contents-sql (assoc params :record-type "A")))

(defn- get-host-ipv6-sql [params]
  (get-record-contents-sql (assoc params :record-type "AAAA")))

(defn- get-host-sshfps-sql [params]
  (get-record-contents-sql (assoc params :record-type "SSHFP")))

(defn- get-host-ipv4-impl [store params]
  (first (exec! store (get-host-ipv4-sql params))))

(defn- get-host-ipv6-impl [store params]
  (first (exec! store (get-host-ipv6-sql params))))

(defn- get-host-sshfps-impl [store params]
  (exec! store (get-host-sshfps-sql params)))

(defrecord SqlDataStore [datasource]

  datastore/IDataStore

  (set-host-ipv4 [_ domain host ip]
    (set-host-ipv4-impl datasource
                        {:domain domain :host host}
                        ip))
  (set-host-ipv6 [_ domain host ip]
    (set-host-ipv6-impl datasource
                        {:domain domain :host host}
                        ip))
  (set-host-sshfps [_ domain host sshfps]
    (set-host-sshpfs-impl datasource
                        {:domain domain :host host}
                        sshfps))

  (get-host-ipv4 [_ domain host]
    (get-host-ipv4-impl datasource {:domain domain :host host}))
  (get-host-ipv6 [_ domain host]
    (get-host-ipv6-impl datasource {:domain domain :host host}))
  (get-host-sshfps [_ domain host]
    (get-host-sshfps-impl datasource {:domain domain :host host})))

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
