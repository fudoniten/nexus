(ns nexus.pdns-datastore
  "A datastore that makes every DNS change through the PowerDNS HTTP API.

  Writing rows straight into PowerDNS's database left three jobs undone,
  and a stack of timers and triggers grew up to do them after the fact:
  nothing refused a CNAME beside other data, nothing rectified the zone for
  DNSSEC, and nothing raised the SOA serial, so secondaries never learned of
  the change. The API does all three as part of the write. A PATCH is one
  transaction, PowerDNS rejects a CNAME conflict with 422, rectifies the
  zone (api-rectify), raises the serial (the zone's SOA-EDIT-API metadata),
  and then sends NOTIFY to the secondaries itself.

  Reads still go to the database through the SQL datastore. They are plain
  SELECTs, and the API has no cheaper way to fetch one rrset.

  ACME challenges keep their bookkeeping in the `challenges` table, which
  now holds each challenge's secret. The TXT rrset for a challenge name is
  rebuilt from every active challenge on that name, so two certificates
  validating the same name at once (a wildcard and its apex) never delete
  each other's record."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [honey.sql :as sql]
            [honey.sql.helpers :as h]
            [next.jdbc :as jdbc]
            [slingshot.slingshot :refer [throw+]]
            [nexus.datastore :as datastore]
            [nexus.sql-datastore :as sql-store])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.time Duration]))

;; --- PowerDNS HTTP API ---

(defn- store-error [error message & [data]]
  (merge {::datastore/error error ::datastore/message message} data))

(defn- api-error
  "Translate a failed API response into a datastore error. 422 is PowerDNS
  refusing the change itself; its message says why."
  [status body zone]
  (let [message (or (try (:error (json/read-str body :key-fn keyword))
                         (catch Exception _ nil))
                    body)]
    (cond
      (= status 404)
      (store-error :no-such-zone (format "no zone %s on this server" zone))

      (and (= status 422) (str/includes? message "Conflicts with pre-existing RRset"))
      (store-error :conflict message)

      (= status 422)
      (store-error :invalid message)

      :else
      (store-error :api-error (format "PowerDNS API returned %s: %s" status message)))))

(defn make-api
  "A PowerDNS API client: base-url like http://127.0.0.1:8081, api-key the
  server's api-key."
  [{:keys [base-url api-key server-id timeout-seconds]
    :or   {server-id "localhost" timeout-seconds 10}}]
  {:base-url    (str/replace base-url #"/+$" "")
   :api-key     api-key
   :server-id   server-id
   :timeout     (Duration/ofSeconds timeout-seconds)
   :http-client (-> (HttpClient/newBuilder)
                    (.connectTimeout (Duration/ofSeconds timeout-seconds))
                    (.build))})

(defn canonical-zone
  "PowerDNS's API names zones with a trailing dot."
  [domain]
  (str (str/lower-case domain) "."))

(defn fqdn
  "The canonical (trailing-dot) name of host within domain."
  [host domain]
  (str (str/lower-case (name host)) "." (canonical-zone domain)))

(defn patch-rrsets!
  "Apply rrset changes to a zone in one transaction. Throws a store error
  (see nexus.datastore/store-error?) if PowerDNS refuses."
  [{:keys [base-url api-key server-id timeout ^HttpClient http-client]} domain rrsets]
  (let [zone (canonical-zone domain)
        req  (-> (HttpRequest/newBuilder
                  (URI. (format "%s/api/v1/servers/%s/zones/%s" base-url server-id zone)))
                 (.timeout timeout)
                 (.header "X-API-Key" api-key)
                 (.header "Content-Type" "application/json")
                 (.method "PATCH" (HttpRequest$BodyPublishers/ofString
                                   (json/write-str {:rrsets rrsets})))
                 (.build))
        ^HttpResponse resp (.send http-client req (HttpResponse$BodyHandlers/ofString))
        status (.statusCode resp)]
    (when-not (<= 200 status 299)
      (throw+ (api-error status (.body resp) zone)))
    rrsets))

(defn replace-rrset [name type ttl contents]
  {:name       name
   :type       type
   :ttl        ttl
   :changetype "REPLACE"
   :records    (mapv (fn [content] {:content content :disabled false})
                     (distinct contents))})

(defn delete-rrset [name type]
  {:name name :type type :changetype "DELETE"})

(defn- rrset
  "REPLACE name/type with contents, or DELETE it when there are none."
  [name type ttl contents]
  (if (seq contents)
    (replace-rrset name type ttl contents)
    (delete-rrset name type)))

;; --- Host records ---

(defn host-rrsets
  "The rrset changes that make a host's records exactly batch-data: each of
  A, AAAA and SSHFP is replaced when present and deleted when absent."
  [host domain ttl {:keys [ipv4 ipv6 sshfps]}]
  (let [name (fqdn host domain)]
    [(rrset name "A"     ttl (when ipv4 [(str ipv4)]))
     (rrset name "AAAA"  ttl (when ipv6 [(str ipv6)]))
     (rrset name "SSHFP" ttl sshfps)]))

;; --- Challenge records ---

(def ^:private challenge-ttl
  "Short, so a resolver that cached the name before the record existed
  forgets it quickly."
  60)

(def ^:private valid-secret
  "ACME key authorizations are base64url digests. Anything else -- quotes
  above all, which would end the TXT string -- is refused."
  #"^[A-Za-z0-9_\-+/=.]{1,255}$")

(defn- txt-content [secret]
  (str "\"" secret "\""))

(defn- active-secrets-sql [domain-id hostname]
  (-> (h/select :secret)
      (h/from :challenges)
      (h/where [:= :domain_id domain-id]
               [:= :hostname hostname]
               [:= :active true]
               [:<> :secret nil])
      (h/order-by :created_at)))

(defn- challenge-rrset
  "The TXT rrset for one challenge name, rebuilt from every active
  challenge on it."
  [tx domain-id domain hostname]
  (let [secrets (->> (jdbc/execute! tx (sql/format (active-secrets-sql domain-id hostname)))
                     (map :challenges/secret))]
    (rrset (fqdn hostname domain) "TXT" challenge-ttl (map txt-content secrets))))

(def ^:private challenge-lock
  "Challenge writes recompute a whole rrset from the table, so two at once
  on the same name could each publish a set missing the other's secret.
  One server process owns these writes, so one lock is enough."
  (Object.))

(defn- domain-id! [sql-store domain]
  (or (sql-store/get-domain-id sql-store domain)
      (throw+ (store-error :no-such-zone (format "no zone %s on this server" domain)))))

(defn create-challenge!
  [{:keys [api sql]} domain host challenge-id secret]
  (when-not (and (string? host) (not (str/blank? host)))
    (throw+ (store-error :invalid "challenge host is missing")))
  (when-not (and (string? secret) (re-matches valid-secret secret))
    (throw+ (store-error :invalid "challenge secret is not a base64url token")))
  (let [domain-id (domain-id! sql domain)
        hostname  (str/lower-case host)]
    (locking challenge-lock
      (jdbc/with-transaction [tx (:datasource sql)]
        (jdbc/execute! tx (sql/format
                           (-> (h/insert-into :challenges)
                               (h/values [{:domain_id    domain-id
                                           :challenge_id (parse-uuid (str challenge-id))
                                           :hostname     hostname
                                           :secret       secret}]))))
        ;; Publish before committing: if PowerDNS refuses, the row rolls
        ;; back with it and the table never claims a record DNS lacks.
        (patch-rrsets! api domain [(challenge-rrset tx domain-id domain hostname)])))
    challenge-id))

(defn- deactivate-and-republish!
  "Mark the challenges matched by where-clause inactive, then rebuild the
  TXT rrset of every name they were on. Returns the number deactivated."
  [{:keys [api sql]} where-clause]
  (locking challenge-lock
    (jdbc/with-transaction [tx (:datasource sql)]
      (let [rows (jdbc/execute! tx (sql/format
                                    (-> (h/update :challenges)
                                        (h/set {:active false})
                                        (h/where [:= :active true] where-clause)
                                        (h/returning :domain_id :hostname))))
            names (distinct (map (juxt :challenges/domain_id :challenges/hostname) rows))]
        (doseq [[domain-id hostname] names]
          (let [domain (->> (jdbc/execute! tx (sql/format (-> (h/select :name)
                                                              (h/from :domains)
                                                              (h/where [:= :id domain-id]))))
                            first
                            :domains/name)]
            (patch-rrsets! api domain [(challenge-rrset tx domain-id domain hostname)])))
        (count rows)))))

(defn delete-challenge!
  [{:keys [sql] :as store} domain challenge-id]
  (let [domain-id (domain-id! sql domain)]
    (pos? (deactivate-and-republish! store
                                     [:and
                                      [:= :domain_id domain-id]
                                      [:= :challenge_id (parse-uuid (str challenge-id))]]))))

(defn expire-challenges!
  [store max-age-seconds]
  (deactivate-and-republish! store
                             [:< :created_at
                              [:raw (format "NOW() - INTERVAL '%d seconds'"
                                            (long max-age-seconds))]]))

;; --- The datastore ---

(defrecord PdnsDataStore [api sql ttl verbose]

  datastore/IDataStore

  (set-host-ipv4 [_ domain host ip]
    (patch-rrsets! api domain [(replace-rrset (fqdn host domain) "A" ttl [(str ip)])])
    ip)
  (set-host-ipv6 [_ domain host ip]
    (patch-rrsets! api domain [(replace-rrset (fqdn host domain) "AAAA" ttl [(str ip)])])
    ip)
  (set-host-sshfps [_ domain host sshfps]
    (patch-rrsets! api domain [(rrset (fqdn host domain) "SSHFP" ttl sshfps)])
    sshfps)
  (set-host-batch [_ domain host batch-data]
    (when verbose
      (println (format "batch update for %s.%s: %s" host domain batch-data)))
    (patch-rrsets! api domain (host-rrsets host domain ttl batch-data))
    batch-data)

  (get-host-ipv4 [_ domain host]   (datastore/get-host-ipv4 sql domain host))
  (get-host-ipv6 [_ domain host]   (datastore/get-host-ipv6 sql domain host))
  (get-host-sshfps [_ domain host] (datastore/get-host-sshfps sql domain host))

  (get-challenge-records [_ domain]
    (datastore/get-challenge-records sql domain))
  (create-challenge-record [self domain host challenge-id secret]
    (create-challenge! self domain host challenge-id secret))
  (delete-challenge-record [self domain challenge-id]
    (delete-challenge! self domain challenge-id))
  (expire-challenge-records [self max-age-seconds]
    (expire-challenges! self max-age-seconds))

  (list-all-records [_]
    (datastore/list-all-records sql)))

(defn connect
  "A PdnsDataStore: writes through the PowerDNS API at pdns-api-url, reads
  from the database described by the remaining options."
  [{:keys [pdns-api-url pdns-api-key-file record-ttl verbose] :as opts
    :or   {record-ttl 3600}}]
  (->PdnsDataStore (make-api {:base-url pdns-api-url
                              :api-key  (str/trim (slurp pdns-api-key-file))})
                   (sql-store/connect opts)
                   record-ttl
                   (boolean verbose)))
