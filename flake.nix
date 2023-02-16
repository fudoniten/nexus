{
  description = "Nexus Server & Client";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-22.05";
    utils.url = "github:numtide/flake-utils";
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, clj-nix, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages."${system}";
        cljpkgs = clj-nix.packages."${system}";
      in {
        packages = let jdkRunner = pkgs.jdk17_headless;
        in {
          nexus-keygen = cljpkgs.mkCljBin {
            projectSrc = ./crypto;
            name = "org.fudo/nexus-keygen";
            main-ns = "nexus.keygen";
            inherit jdkRunner;
            lockfile = ./crypto/deps-lock.json;
          };
          nexus-crypto = cljpkgs.mkCljLib {
            projectSrc = ./crypto;
            name = "org.fudo/nexus.crypto";
            inherit jdkRunner;
            lockfile = ./crypto/deps-lock.json;
          };
          nexus-server = cljpkgs.mkCljBin {
            projectSrc = ./server;
            name = "org.fudo/nexus-server";
            main-ns = "nexus.server.cli";
            inherit jdkRunner;
            lockfile = ./server/deps-lock.json;
          };
          nexus-client = cljpkgs.mkCljBin {
            projectSrc = ./client;
            name = "org.fudo/nexus-client";
            main-ns = "nexus.client.cli";
            inherit jdkRunner;
            lockfile = ./client/deps-lock.json;
          };
        };

        # defaultPackage = self.packages."${system}".nexus-client;

        devShell = let
          update-deps = let
            update = pkgs.writeShellScript "update-project-deps.sh" ''
              ${clj-nix.packages."${system}".deps-lock}/bin/deps-lock $@
            '';
          in pkgs.writeShellScriptBin "update-deps.sh" ''
            for dir in crypto server client; do
              pushd .
              cd $dir
              ${update}
              popd
            done
          '';
        in pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
            update-deps
            self.packages."${system}".nexus-keygen
          ];
        };
      }) // {
        overlay = final: prev: {
          inherit (self.packages."${prev.system}") nexus-keygen;
        };

        nixosModules = { server = import ./server/module.nix self.packages; };
      };
}
