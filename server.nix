packages:

{ config, lib, pkgs, ... }:

with lib;
let
  inherit (pkgs."${pkgs.system}") nexus-server;
  cfg = config.nexus.server;
  db-cfg = config.nexus.database;

  host-alias-map = pkgs.writeText "nexus-host-alias-map.json"
    (builtins.toJSON cfg.client-alias-map);

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    services.nginx = {
      enable = true;
      virtualHosts = genAttrs cfg.hostnames (_: {
        enableACME = true;
        forceSSL = true;

        locations."/".proxyPass =
          "http://127.0.0.1:${toString cfg.internal-port}";
      });
    };

    systemd.services.nexus-server = {
      path = [ nexus-server ];
      wantedBy = [ "network-online.target" ];
      serviceConfig = {
        ExecStart = pkgs.writeShellScript "nexus-server-start.sh"
          (concatStringsSep " " ([
            "nexus-server"
            "--host-keys=$CREDENTIALS_DIRECTORY/host-keys.json"
            "--challenge-keys=$CREDENTIALS_DIRECTORY/challenge-keys.json"
            "--host-alias-map=${host-alias-map}"
            "--database=${db-cfg.database}"
            "--database-user=${cfg.database.user}"
            "--database-password-file=$CREDENTIALS_DIRECTORY/db.passwd"
            "--database-host=${db-cfg.host}"
            "--database-port=${toString db-cfg.port}"
            "--listen-host=127.0.0.1"
            "--listen-port=${toString cfg.internal-port}"
          ] ++ (optional cfg.verbose "--verbose")));

        ExecStartPre = let
          ncCmd =
            "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${toString db-cfg.port}";
        in pkgs.writeShellScript "powerdns-initialize-db-prep.sh"
        "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'";

        LoadCredential = [
          "db.passwd:${cfg.database.password-file}"
          "host-keys.json:${cfg.client-keys-file}"
          "challenge-keys.json:${cfg.challenge-keys-file}"
        ];
        DynamicUser = true;
        # Needs access to network for Postgresql
        PrivateNetwork = false;
        PrivateUsers = true;
        PrivateDevices = true;
        PrivateTmp = true;
        PrivateMounts = true;
        ProtectControlGroups = true;
        ProtectKernelTunables = true;
        ProtectKernelModules = true;
        # ProtectSystem = "full";
        ProtectHostname = true;
        ProtectHome = true;
        ProtectClock = true;
        LockPersonality = true;
        RestrictRealtime = true;
        LimitNOFILE = "4096";
        # PermissionsStartOnly = true;
        # NoNewPrivileges = true;
        AmbientCapabilities = [ "CAP_NET_BIND_SERVICE" ];
        TimeoutStartSec = "180";
        SecureBits = "keep-caps";
        Restart = "always";
      };
      unitConfig.ConditionPathExists =
        [ cfg.database.password-file cfg.client-keys-file ];
    };
  };
}
