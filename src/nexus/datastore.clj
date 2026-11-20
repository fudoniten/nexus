(ns nexus.datastore
  "Protocol defining the interface for DNS record storage and retrieval")

(defprotocol IDataStore
  "Protocol for managing DNS records and ACME challenge data"
  
  (health-check 
    [_]
    "Perform a health check on the datastore")
  
  (set-host-ipv4
    [_ domain host ipv4]
    "Set the IPv4 A record for a host in a domain")
  
  (set-host-ipv6
    [_ domain host ipv6]
    "Set the IPv6 AAAA record for a host in a domain")
  
  (set-host-sshfps
    [_ domain host sshfps]
    "Set the SSHFP records for a host in a domain")
  
  (get-host-ipv4
    [_ domain host]
    "Get the IPv4 A record for a host in a domain")
  
  (get-host-ipv6
    [_ domain host]
    "Get the IPv6 AAAA record for a host in a domain")
  
  (get-host-sshfps
    [_ domain host]
    "Get the SSHFP records for a host in a domain")
  
  (set-host-batch
    [_ domain host batch-data]
    "Set the full record state for a host in one atomic change.
     batch-data is a map that may contain :ipv4, :ipv6, and/or :sshfps keys;
     a key that is absent means the host no longer has records of that type,
     and any it had are removed.")
  
  (get-challenge-records
    [_ domain]
    "Get all active ACME challenge records for a domain")
  
  (create-challenge-record
    [_ domain host challenge-id secret]
    "Create a new ACME challenge record for domain validation")
  
  (delete-challenge-record  
    [_ domain challenge-id]
    "Delete an ACME challenge record after validation")
  
  (expire-challenge-records
    [_ max-age-seconds]
    "Remove ACME challenge records created more than max-age-seconds ago")

  (expire-server-records
    [_ cutoff]
    "Remove the records Nexus itself wrote that have not been refreshed
     since cutoff (a java.time.Instant): hosts that stopped reporting.
     Records from the database init or written by hand are never removed.
     Returns the number of rrsets removed.")

  (last-server-record-refresh
    [_]
    "When a record Nexus wrote was last refreshed (a java.time.Instant), or
     nil if it has written none.")

  (list-all-records
    [_]
    "List all DNS records in the database with domain information"))

(defn store-error?
  "True for the error maps a datastore throws (with slingshot's throw+) for
  a write the DNS server refused. Usable directly as a slingshot catch
  selector."
  [x]
  (and (map? x) (contains? x ::error)))

(defn error-response
  "The HTTP response for a store-error? map: a client error the caller can
  act on, rather than a bare 500."
  [{::keys [error message]}]
  {:status (case error
             :conflict     409
             :no-such-zone 404
             :invalid      400
             502)
   :body   (str "rejected: " message)})
