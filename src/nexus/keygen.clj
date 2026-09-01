(ns nexus.keygen
  "Command-line utility for generating and writing cryptographic keys."
  (:require [nexus.crypto :as crypto]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log])
  (:gen-class))

(def cli-opts
  "Command-line options for the key generation utility."
  [["-a" "--algorithm ALGO" "Algorithm key to generate." :default "HmacSHA512"]
   ["-s" "--seed SEED"      "Seed used to generate key."]
   ["-K" "--keypair"
    "Generate an Ed25519 public/private keypair instead of a single symmetric key. Ignores --algorithm and --seed. Writes the private key to FILENAME and the public key to FILENAME.pub."]
   ["-h" "--help"]
   ["-v" "--verbose" "Enable verbose logging."]])

(defn usage
  "Generates a usage message for the command-line utility.
  Optionally includes error messages."
  ([summary]
   (usage summary []))
  ([summary errors]
   (->> (concat errors
                ["usage: nexus-generate-key [opts] <FILENAME>"
                 ""
                 "Options:"
                 summary])
        (str/join \newline))))

(defn msg-quit
  "Prints a message and exits the program with the given status code."
  [status msg]
  (println msg)
  (System/exit status))

(defn write-key
  "Writes the encoded key to the specified filename."
  [{:keys [key filename]}]
  (log/debug "Writing key to file:" {:filename filename})
  (try
    (with-open [file (io/writer filename)]
      (.write file (crypto/encode-key key)))
    (catch Exception e
      (throw (ex-info "Failed to write key to file" {:filename filename} e)))))

(defn- restrict-to-owner!
  "Best-effort: restrict a file to owner read/write only. A private key file
  should never be group/world readable. No-ops on non-POSIX filesystems."
  [filename]
  (try
    (java.nio.file.Files/setPosixFilePermissions
     (.toPath (io/file filename))
     (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
    (catch UnsupportedOperationException _ nil)))

(defn write-keypair
  "Writes an Ed25519 keypair to disk: the private key to filename, and the
  public key to filename.pub. The private key file is restricted to
  owner-only permissions; the public key is not sensitive."
  [{:keys [keypair filename]}]
  (let [{:keys [public-key private-key]} keypair
        pub-filename (str filename ".pub")]
    (log/debug "Writing keypair to files:" {:private filename :public pub-filename})
    (try
      (with-open [file (io/writer filename)]
        (.write file (crypto/encode-private-key private-key)))
      (restrict-to-owner! filename)
      (with-open [file (io/writer pub-filename)]
        (.write file (crypto/encode-public-key public-key)))
      (catch Exception e
        (throw (ex-info "Failed to write keypair to file" {:filename filename} e))))))

(defn gen-key
  "Generates a cryptographic key using the specified algorithm and optional seed."
  [{:keys [algorithm seed]}]
  (try
    (if seed
      (crypto/generate-key algorithm seed)
      (crypto/generate-key algorithm))
    (catch Exception e
      (throw (ex-info "Failed to generate key" {:algorithm algorithm :seed seed} e)))))

(defn -main
  "Main entry point for the command-line utility. Parses arguments and generates a key file."
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-opts)]
    (when (:verbose options) (log/info "Verbose logging enabled"))
    (when (seq errors) (msg-quit 1 (usage summary errors)))
    (when (:help options) (msg-quit 0 (usage summary)))
    (when (not (= 1 (count arguments))) (msg-quit 1 (usage summary ["missing required paramater FILENAME"])))
    (let [filename (first arguments)]
      (if (:keypair options)
        (write-keypair {:filename filename :keypair (crypto/generate-keypair)})
        (-> options
            (assoc :filename filename)
            (assoc :key      (gen-key options))
            (write-key))))))
