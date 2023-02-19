packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.fudo.nexus.client;
in {
  import = [ ../options.nix ];

  config = {
    systemd = {
      service.nexus-client = {
        path = [ nexus-client ];
        serviceConfig = { DynamicUser = true; };
      };

      paths = {

      };
    };
  };
}
