{
  description = "Nexus Crypto Library and Keygen";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-24.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        inherit (helpers.packages."${system}") mkClojureBin mkClojureLib;
      in {
        packages = rec {
          default = nexus-keygen;
          nexus-keygen = mkClojureBin {
            name = "org.fudo/nexus-keygen";
            primaryNamespace = "nexus.keygen";
            src = ./.;
          };
          nexus-crypto = mkClojureLib {
            name = "org.fudo/nexus.crypto";
            src = ./.;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps { }) ];
          };
        };
      });
}
