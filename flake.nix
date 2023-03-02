{
  description = "Nexus Server & Client";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-22.05";
    nexus-client = {
      url = "git+https://git.fudo.org/fudo-public/nexus-client.git";
      flake = false;
    };
    nexus-server = {
      url = "git+https://git.fudo.org/fudo-public/nexus-server.git";
      flake = false;
    };
    nexus-crypto = {
      url = "git+https://git.fudo.org/fudo-public/nexus-crypto.git";
      flake = false;
    };
    utils.url = "github:numtide/flake-utils";
  };

  outputs =
    { self, nixpkgs, utils, nexus-client, nexus-server, nexus-crypto, ... }:

    utils.lib.eachDefaultSystem (system: {
      packages = rec {
        default = nexus-client;
        nexus-client = nexus-client.packages."${system}".nexus-client;
        nexus-crypto = nexus-client.packages."${system}".nexus-crypto;
        nexus-server = nexus-client.packages."${system}".nexus-server;
      };
    }) // {
      nixosModules = {
        nexus-client = import ./client.nix self.packages;
        nexus-powerdns = import ./powerdns.nix;
        nexus-server = import ./server.nix self.package;
      };
    };
}
