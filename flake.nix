{
  description = "Nexus Server & Client";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    nexus-client = {
      url = "github:fudoniten/nexus-client";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nexus-crypto = {
      url = "github:fudoniten/nexus-crypto";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nexus-server = {
      url = "github:fudoniten/nexus-server";
      inputs.nixpkgs.follows = "nixpkgs";
    };
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
        nexus-server = import ./server.nix self.packages;
      };
    };
}
