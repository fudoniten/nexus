(ns nexus.datastore)

(defprotocol IDataStore
  (set-host-ipv4 [_ host ipv4])
  (set-host-ipv6 [_ host ipv6])
  (set-host-sshfps [_ host sshfps])
  (get-host-ipv4 [_ host])
  (get-host-ipv6 [_ host])
  (get-host-sshfps [_ host]))
