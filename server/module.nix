packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-server = packages."${pkgs.system}".nexus-server;
  cfg = config.fudo.nexus.server;
in {
  imports = [ ../options.nix ];

  config = {
    services = {
      nginx = {
        enable = true;
        virtualHosts."${cfg.hostname}" = {
          enableACME = true;
          forceSSL = true;

          locations."/".proxyPass = "http://127.0.0.1:${cfg.port}";
        };
      };
    };

    systemd.services.nexus-server = {
      path = [ nexus-server ];
      serviceConfig = {
        ExecStart = let
          host-keys-file = pkgs.writeText "host-keys.json"
            (toJSON (mapAttrs (_: filename: readFile filename) cfg.host-keys));
        in concatStringsSep " " [
          "nexus-server"
          "--host-keys=$CREDENTIALS_DIRECTORY/host-keys.json"
          "--database=${cfg.database.database}"
          "--database-user=${cfg.database.user}"
          "--database-password-file=$CREDENTIALS_DIRECTORY/db.passwd"
          "--database-host=${cfg.database.host}"
          "--database-port=${cfg.database.port}"
          "--listen-host=127.0.0.1"
          "--listen-port=${toString cfg.port}"
        ];

        LoadCredentials = [
          "db.passwd:${cfg.database.password-file}"
          "host-keys.json:${host-keys-file}"
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
        ProtectSystem = true;
        ProtectHostname = true;
        ProtectHome = true;
        ProtectClock = true;
        LockPersonality = true;
        RestrictRealtime = true;
        LimitNOFILE = "4096";
        PermissionsStartOnly = true;
        NoNewPrivileges = true;
        AmbientCapabilities = [ "CAP_NET_BIND_SERVICE" ];
        SecureBits = "keep-caps";

        Restart = "always";
      };
    };
  };
}
