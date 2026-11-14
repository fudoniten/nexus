{
  description = "Nexus DDNS System - Monorepo";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-26.05";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure = {
      url = "github:fudoniten/fudo-clojure/2156a2203ba07c059e29f6948e59e669378897af";
      inputs = {
        nixpkgs.follows = "nixpkgs";
        helpers.follows = "helpers";
      };
    };
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        inherit (helpers.legacyPackages."${system}")
          mkClojureBin mkClojureLib mkClojureTests;

        # Local Clojure libraries (no longer fetched from git!)
        # Use preppedSrc instead of JAR for deps-lock to get transitive dependencies
        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure.preppedSrc;
        };

      in {
        packages = rec {
          default = nexus-server;

          # Crypto library (for external consumers if needed)
          #
          # cljLibs matters here as much as it does for the binaries below:
          # without it the build has no pre-fetched org.fudo/fudo-clojure and
          # the Clojure tooling shells out to `git clone` inside the nix
          # sandbox, where there is neither git nor network -- surfacing as
          # "Exec failed, error: 2 (No such file or directory)".
          nexus-crypto = mkClojureLib {
            name = "org.fudo/nexus.crypto";
            src = ./.;
            inherit cljLibs;
          };

          # Key generation utility
          nexus-keygen = mkClojureBin {
            name = "org.fudo/nexus-keygen";
            primaryNamespace = "nexus.keygen";
            src = ./.;
            inherit cljLibs;
          };

          # DDNS Server
          nexus-server = mkClojureBin {
            name = "org.fudo/nexus-server";
            primaryNamespace = "nexus.server.cli";
            src = ./.;
            inherit cljLibs;
          };
        };

        devShells = rec {
          # General development shell: clojure CLI for running tests
          # (clojure -M:test) and lint (clojure -M:lint, which bootstraps
          # clj-kondo itself via deps.edn's :lint alias).
          default = pkgs.mkShell { buildInputs = with pkgs; [ clojure ]; };

          # Update deps-lock.json (without test dependencies)
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.legacyPackages."${system}"; [
              (updateClojureDeps { deps = cljLibs; })
              update-git-deps
            ];
          };

          # Update deps-lock.json including test dependencies
          updateDepsWithTests = pkgs.mkShell {
            buildInputs = with helpers.legacyPackages."${system}";
              [
                (updateClojureDeps {
                  deps = cljLibs;
                  aliases = [ "test" ];
                })
              ];
          };
        };

        # Run tests with eftest using deps-lock.json
        checks = {
          # Unit tests (existing)
          nexus-tests = mkClojureTests {
            name = "nexus";
            src = ./.;
            testAlias = "test";
            inherit cljLibs;
          };
        };
      }) // {
        # NixOS modules now reference packages from same flake
        nixosModules = {
          nexus-client = import ./nix/client.nix self.packages;
          nexus-powerdns = import ./nix/powerdns.nix;
          nexus-server = import ./nix/server.nix self.packages;
        };
      };
}
