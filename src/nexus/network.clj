(ns nexus.network
  "Per-domain address restrictions: which networks a client may publish an
  address from, for each domain.

  A domain with no entry accepts any address. A domain with one accepts
  only addresses inside one of its CIDR blocks -- e.g. a Tailscale-only
  domain lists 100.64.0.0/10 and fd7a:115c:a1e0::/48, so an address from
  some other overlay (Nebula, a LAN) is refused rather than published."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net InetAddress]))

(defn- parse-address
  "A literal IP string as an InetAddress, or nil. Only numeric literals are
  accepted, so this never triggers a DNS lookup."
  ^InetAddress [s]
  (let [s (str/trim (str s))]
    (when (and (seq s) (re-matches #"[0-9a-fA-F.:]+" s))
      (try (InetAddress/getByName s)
           (catch Exception _ nil)))))

(defn parse-cidr
  "Parse \"network/prefix\" into {:network addr-bytes :prefix n :cidr s}. A bare
  address is a single-host block. Throws IllegalArgumentException on
  anything else, so a typo in the configuration stops the server at startup
  instead of silently allowing (or refusing) everything."
  [cidr]
  (let [[addr prefix & more] (str/split (str/trim (str cidr)) #"/")
        a     (parse-address addr)
        addr-bytes (when a (.getAddress ^InetAddress a))
        max-prefix (when addr-bytes (* 8 (alength ^bytes addr-bytes)))
        n     (cond (nil? prefix) max-prefix
                    (re-matches #"\d{1,3}" prefix) (Integer/parseInt prefix))]
    (when (or more (nil? a) (nil? n) (> n max-prefix))
      (throw (IllegalArgumentException. (str "not a valid CIDR block: " cidr))))
    {:network addr-bytes :prefix n :cidr (str cidr)}))

(defn in-block?
  "True if ip (anything whose str is an address literal) falls inside the
  parsed CIDR block. A different address family is never a match."
  [{:keys [^bytes network prefix]} ip]
  (boolean
   (when-let [a (parse-address ip)]
     (let [ab (.getAddress ^InetAddress a)]
       (and (= (alength ab) (alength network))
            (loop [bits prefix i 0]
              (cond
                (<= bits 0) true
                (>= bits 8) (and (= (aget ab i) (aget network i))
                                 (recur (- bits 8) (inc i)))
                :else (let [mask (bit-and 0xff (bit-shift-left 0xff (- 8 bits)))]
                        (= (bit-and (aget ab i) mask)
                           (bit-and (aget network i) mask))))))))))

(defn make-restrictions
  "Build the restriction map from {domain [cidr ...]}. Domains with an empty
  list are left out, i.e. unrestricted."
  [domain-networks]
  (into {}
        (for [[domain cidrs] domain-networks
              :when (seq cidrs)]
          [(name domain) (mapv parse-cidr cidrs)])))

(defn read-restrictions
  "Read {domain: [cidr, ...]} from a JSON file. nil file -> no restrictions."
  [filename]
  (if filename
    (with-open [r (io/reader filename)]
      (make-restrictions (json/read r)))
    {}))

(defn allowed?
  "True if ip may be published in domain."
  [restrictions domain ip]
  (if-let [blocks (get restrictions (name domain))]
    (boolean (some #(in-block? % ip) blocks))
    true))

(defn allowed-networks
  "The CIDR strings domain is restricted to, or nil if unrestricted."
  [restrictions domain]
  (some->> (get restrictions (name domain)) (mapv :cidr)))
