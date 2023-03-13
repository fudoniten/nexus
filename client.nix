packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.nexus.client;

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    systemd = {
      services = {
        nexus-client = {
          path = [ nexus-client pkgs.openssh ];
          wantedBy = [ "network-online.target" ];
          serviceConfig = {
            DynamicUser = true;
            RuntimeDirectory = "nexus-client";
            LoadCredential = [ "hmac.key:${cfg.hmac-key-file}" ];
            ExecStart = pkgs.writeShellScript "nexus-client.sh"
              (concatStringsSep " " ([
                "nexus-client"
                "--verbose"
                "--port=${toString config.nexus.server.port}"
                "--delay-seconds=${toString cfg.delay-seconds}"
                "--hostname=${cfg.hostname}"
                "--key-file=$CREDENTIALS_DIRECTORY/hmac.key"
              ] ++ (map (srv: "--server=${srv}") cfg.servers)
                ++ (map (dom: "--domain=${dom}") cfg.domains)
                ++ (optional cfg.ipv4 "--ipv4") ++ (optional cfg.ipv6 "--ipv6")
                ++ (map (sshfp: ''--sshfp="${sshfp}"'') cfg.sshfps)));
          };
        };
      };
    };
  };
}
