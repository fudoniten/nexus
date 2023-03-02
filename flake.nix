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

  outputs = { self, nixpkgs, utils, ... }@inputs:

    utils.lib.eachDefaultSystem (system: {
      packages = rec {
        default = nexus-client;
        nexus-client = inputs.nexus-client.packages."${system}".nexus-client;
        nexus-keygen = inputs.nexus-crypto.packages."${system}".nexus-keygen;
        nexus-server = inputs.nexus-server.packages."${system}".nexus-server;
      };
    }) // {
      nixosModules = {
        nexus-client = import ./client.nix self.packages;
        nexus-powerdns = import ./powerdns.nix;
        nexus-server = import ./server.nix self.package;
      };
    };
}
