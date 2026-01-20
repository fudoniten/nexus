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
  
  (get-challenge-records
    [_ domain]
    "Get all active ACME challenge records for a domain")
  
  (create-challenge-record
    [_ domain host challenge-id secret]
    "Create a new ACME challenge record for domain validation")
  
  (delete-challenge-record  
    [_ domain challenge-id]
    "Delete an ACME challenge record after validation"))
