{
  description = "Nexus Crypo Library";

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
        update-deps = pkgs.writeShellScriptBin "update-deps.sh" ''
          ${clj-nix.packages."${system}".deps-lock}/bin/deps-lock
        '';
      in {
        packages = {
          nexus-keygen = cljpkgs.mkCljBin {
            projectSrc = ./.;
            name = "org.fudo/nexus-keygen";
            main-ns = "nexus.keygen";
            jdkRunner = pkgs.jdk17_headless;
          };
          nexus-crypto = cljpkgs.mkCljLib {
            projectSrc = ./.;
            name = "org.fudo/nexus.crypto";
            jdkRunner = pkgs.jdk17_headless;
          };
        };

        defaultPackage = self.packages."${system}".nexus-keygen;

        devShell = pkgs.mkShell {
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

        nixosModule = import ./module.nix self.overlay;
      };
}
