(ns nexus.host-alias-map
  "Namespace for managing host alias mappings"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(defn read-json-file
  "Read a JSON file into a map with keyword keys"
  [filename]
  (with-open [file (io/reader filename)]
    (json/read file {:key-fn keyword})))

(defn make-domain-fqdns
  "Create fully-qualified domain names from aliases"
  [{:keys [domain aliases]}]
  (map (fn [alias] (format "%s.%s" alias domain)) aliases))

(defn make-host-pairs
  "Create pairs of [fqdn host] for each alias"
  [[host domain-aliases]]
  (->> domain-aliases
       (mapcat make-domain-fqdns)
       (map (fn [fqdn] [fqdn host]))))

(defprotocol IHostAliasMap
  (get-host [self host domain]))

(defrecord HostAliasMap [alias-map]
  IHostAliasMap
  (get-host [_ host domain]
    (get alias-map
         (format "%s.%s" (name host) (name domain))
         (keyword host))))

(defn make-mapper
  "Create a HostAliasMap from a JSON file"
  [host-alias-map-file]
  (if (string? host-alias-map-file)
    (->> host-alias-map-file
         (read-json-file)
         (into {} (mapcat make-host-pairs))
         (->HostAliasMap))
    (->HostAliasMap {})))
