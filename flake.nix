{
  description = "Nexus DDNS System - Monorepo";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure = {
      url = "github:fudoniten/fudo-clojure";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        inherit (helpers.packages."${system}")
          mkClojureBin mkClojureLib mkClojureTests;

        # Local Clojure libraries (no longer fetched from git!)
        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure;
        };

      in {
        packages = rec {
          default = nexus-client;

          # Crypto library (for external consumers if needed)
          nexus-crypto = mkClojureLib {
            name = "org.fudo/nexus.crypto";
            src = ./.;
          };

          # Key generation utility
          nexus-keygen = mkClojureBin {
            name = "org.fudo/nexus-keygen";
            primaryNamespace = "nexus.keygen";
            src = ./.;
            inherit cljLibs;
          };

          # DDNS Client
          nexus-client = mkClojureBin {
            name = "org.fudo/nexus-client";
            primaryNamespace = "nexus.client.cli";
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
          default = updateDeps;

          # Update deps-lock.json (without test dependencies)
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps { deps = cljLibs; }) ];
          };

          # Update deps-lock.json including test dependencies
          updateDepsWithTests = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
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
