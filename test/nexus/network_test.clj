(ns nexus.network-test
  (:require [nexus.network :as network]
            [clojure.test :refer [deftest is testing]]))

(def ^:private tailscale-only
  (network/make-restrictions {"fudo.ninja" ["100.64.0.0/10" "fd7a:115c:a1e0::/48"]
                              "open.org"   []}))

(deftest tailscale-only-domain
  (testing "Tailscale addresses are allowed"
    (is (network/allowed? tailscale-only "fudo.ninja" "100.64.0.0"))
    (is (network/allowed? tailscale-only "fudo.ninja" "100.101.2.3"))
    (is (network/allowed? tailscale-only "fudo.ninja" "100.127.255.255"))
    (is (network/allowed? tailscale-only "fudo.ninja" "fd7a:115c:a1e0::1")))
  (testing "anything else is refused"
    (is (not (network/allowed? tailscale-only "fudo.ninja" "100.128.0.1")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "100.63.255.255")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "10.0.0.1")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "1.2.3.4")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "fd7a:115c:a1e1::1")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "2001:db8::1")))
    (is (not (network/allowed? tailscale-only "fudo.ninja" "not-an-ip")))))

(deftest unrestricted-domains
  (is (network/allowed? tailscale-only "open.org" "1.2.3.4"))
  (is (network/allowed? tailscale-only "fudo.dev" "10.0.0.1"))
  (is (nil? (network/allowed-networks tailscale-only "fudo.dev"))))

(deftest bad-cidrs-are-rejected
  (doseq [bad ["1.2.3.4/33" "::/129" "foo/8" "fudo.org/8" "1.2.3.4/8/2" ""]]
    (is (thrown? IllegalArgumentException (network/parse-cidr bad)) bad)))
