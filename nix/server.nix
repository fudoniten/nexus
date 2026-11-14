packages:

{ config, lib, pkgs, ... }:

with lib;
let
  inherit (packages."${pkgs.system}") nexus-server;
  cfg = config.nexus.server;
  db-cfg = config.nexus.database;
  dns-cfg = config.nexus.dns-server;

  host-alias-map = pkgs.writeText "nexus-host-alias-map.json"
    (builtins.toJSON cfg.client-alias-map);

  # Unlike host-keys/challenge-keys above, these are Ed25519 public keys --
  # not secret, so they need no LoadCredential/secret-file handling, just a
  # plain Nix store path built directly from the (committable-in-plaintext)
  # config value, the same way host-alias-map is.
  host-public-keys-json = pkgs.writeText "nexus-host-public-keys.json"
    (builtins.toJSON cfg.host-public-keys);

  challenge-public-keys-json =
    pkgs.writeText "nexus-challenge-public-keys.json"
    (builtins.toJSON cfg.challenge-public-keys);

  # The secret files the unit must wait for before starting. The HMAC key
  # files are only present while clients of that kind are still on /api/v2;
  # a fully migrated deployment has neither, and waiting on a path that will
  # never appear would hang the unit until its start timeout.
  secret-files = [ cfg.database.password-file ]
    ++ (optional (cfg.client-keys-file != null) cfg.client-keys-file)
    ++ (optional (cfg.challenge-keys-file != null) cfg.challenge-keys-file);

  secrets-present =
    concatStringsSep " && " (map (f: "[ -f ${f} ]") secret-files);

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    # Each API needs at least one kind of key. Catching this here gives a
    # build-time error naming the option to set, rather than a server that
    # fails to start on the target host.
    assertions = [
      {
        assertion = dns-cfg.enable;
        message = ''
          nexus.server: makes every record change through the PowerDNS API on
          127.0.0.1, so nexus.dns-server must be enabled on the same host.
        '';
      }
      {
        assertion = cfg.client-keys-file != null || cfg.host-public-keys != { };
        message = ''
          nexus.server: set client-keys-file (legacy HMAC, /api/v2) or
          host-public-keys (Ed25519, /api/v3), or both while hosts migrate.
        '';
      }
      {
        assertion = cfg.challenge-keys-file != null
          || cfg.challenge-public-keys != { };
        message = ''
          nexus.server: set challenge-keys-file (legacy HMAC, /api/v2) or
          challenge-public-keys (Ed25519, /api/v3), or both while challenge
          clients migrate.
        '';
      }
    ];

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
      requires = [ "nexus-powerdns-api-key.service" ];
      wants = [ "nexus-powerdns.service" ];
      after = [ "nexus-powerdns-api-key.service" "nexus-powerdns.service" ];
      serviceConfig = {
        ExecStart = pkgs.writeShellScript "nexus-server-start.sh"
          (concatStringsSep " " ([
            "nexus-server"
            "--host-alias-map=${host-alias-map}"
            "--database=${db-cfg.database}"
            "--database-user=${cfg.database.user}"
            "--database-password-file=$CREDENTIALS_DIRECTORY/db.passwd"
            "--database-host=${db-cfg.host}"
            "--database-port=${toString db-cfg.port}"
            "--listen-host=127.0.0.1"
            "--listen-port=${toString cfg.internal-port}"
            "--pdns-api-url=http://127.0.0.1:${toString dns-cfg.api-port}"
            "--pdns-api-key-file=$CREDENTIALS_DIRECTORY/pdns-api.key"
          ] ++ (optional (cfg.client-keys-file != null)
            "--host-keys=$CREDENTIALS_DIRECTORY/host-keys.json")
          ++ (optional (cfg.challenge-keys-file != null)
            "--challenge-keys=$CREDENTIALS_DIRECTORY/challenge-keys.json")
          ++ (optional (cfg.host-public-keys != { })
            "--host-public-keys=${host-public-keys-json}")
          ++ (optional (cfg.challenge-public-keys != { })
            "--challenge-public-keys=${challenge-public-keys-json}")
          ++ (optional cfg.verbose "--verbose")));

        ExecStartPre = [
          # Waits only for the secrets this deployment actually has.
          ("+${pkgs.writeShellScript "nexus-wait-for-secrets.sh" ''
            until ${secrets-present}; do
              echo "nexus-server: waiting for secret files to appear..."
              sleep 5
            done
            echo "nexus-server: secret files found, continuing."
          ''}")
          (let
            ncCmd =
              "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${toString db-cfg.port}";
          in pkgs.writeShellScript "pdns-initialize-db-prep.sh"
            "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'")
        ];

        LoadCredential = [
          "db.passwd:${cfg.database.password-file}"
          "pdns-api.key:${dns-cfg.api-key-file}"
        ]
          ++ (optional (cfg.client-keys-file != null)
            "host-keys.json:${cfg.client-keys-file}")
          ++ (optional (cfg.challenge-keys-file != null)
            "challenge-keys.json:${cfg.challenge-keys-file}");
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
    };

  };
}
