{
  description = "Nexus Server & Client";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    nexus-client = {
      url = "git+https://fudo.dev/public/nexus-client.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nexus-crypto = {
      url = "git+https://fudo.dev/public/nexus-crypto.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    nexus-server = {
      url = "git+https://fudo.dev/public/nexus-server.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    utils = {
      url = "github:numtide/flake-utils";
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
