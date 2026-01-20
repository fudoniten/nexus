{
  description = "Nexus Server";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-24.11";
    utils.url = "github:numtide/flake-utils";
    fudo-clojure = {
      url = "github:fudoniten/fudo-clojure";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nexus-crypto = {
      url = "github:fudoniten/nexus-crypto";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, nexus-crypto, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure;
          "org.fudo/nexus.crypto" =
            nexus-crypto.packages."${system}".nexus-crypto;
        };
      in {
        packages = rec {
          default = nexus-server;
          nexus-server = helpers.packages."${system}".mkClojureBin {
            name = "org.fudo/nexus-server";
            primaryNamespace = "nexus.server.cli";
            src = ./.;
            inherit cljLibs;
            version = "0.2.0";
          };
        };

        devShells = rec {
          default = update-deps;
          update-deps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps cljLibs) ];
          };
        };
      });
}
