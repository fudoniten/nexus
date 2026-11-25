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

  # Public keys, not secrets, so they go straight into the Nix store the
  # same way host-alias-map does.
  host-public-keys-json = pkgs.writeText "nexus-host-public-keys.json"
    (builtins.toJSON cfg.host-public-keys);

  # Only restricted domains are listed; the server treats any other domain
  # as accepting every address.
  domain-allowed-networks-json =
    pkgs.writeText "nexus-domain-allowed-networks.json" (builtins.toJSON
      (listToAttrs (map (domainCfg:
        nameValuePair domainCfg.domain-name domainCfg.allowed-networks)
        (filter (domainCfg: domainCfg.allowed-networks != [ ])
          (attrValues config.nexus.domains)))));

  challenge-public-keys-json =
    pkgs.writeText "nexus-challenge-public-keys.json"
    (builtins.toJSON cfg.challenge-public-keys);

  # A host alias is a name the host publishes its own A/AAAA under. A
  # domain alias (nexus.domains.<d>.aliases) or a CNAME in the domain's
  # static records is a CNAME. One name cannot be both: PowerDNS refuses the
  # second write, and before it did, the zone ended up holding both and the
  # secondaries refused every transfer. Catch it here, where the two
  # definitions meet, rather than as a 409 at runtime.
  cnameNames = domain:
    let domainCfg = config.nexus.domains.${domain} or null;
    in if domainCfg == null then
      [ ]
    else
      (attrNames domainCfg.aliases)
      ++ (map (record: removeSuffix ".${domain}" record.name)
        (filter (record: record.type == "CNAME")
          domainCfg.records));

  aliasConflicts = concatLists (mapAttrsToList (host: domainAliases:
    concatMap ({ domain, aliases }:
      map (alias: "${alias}.${domain} (alias of ${host})")
      (filter (alias: elem alias (cnameNames domain)) aliases))
    domainAliases) cfg.client-alias-map);

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    # Caught at build time, naming the option to fix, rather than as a
    # server that fails to start (or refuses every update) on the host.
    assertions = [
      {
        assertion = dns-cfg.enable;
        message = ''
          nexus.server: makes every record change through the PowerDNS API on
          127.0.0.1, so nexus.dns-server must be enabled on the same host.
        '';
      }
      {
        assertion = cfg.host-public-keys != { };
        message = "nexus.server: host-public-keys must name at least one host.";
      }
      {
        assertion = cfg.challenge-public-keys != { };
        message =
          "nexus.server: challenge-public-keys must name at least one challenge client.";
      }
      {
        assertion = aliasConflicts == [ ];
        message = ''
          nexus.server: these names are both a host alias (client-alias-map,
          published as A/AAAA) and a CNAME (nexus.domains.<domain>.aliases or
          a CNAME record). A name can be one or the other:

            ${concatStringsSep "\n    " aliasConflicts}
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
      # Not network-online.target: a target orders itself after the units it
      # wants, and nexus-powerdns runs after network-online.target, so that
      # made an ordering cycle and systemd dropped this job at boot.
      wantedBy = [ "multi-user.target" ];
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
            "--host-public-keys=${host-public-keys-json}"
            "--challenge-public-keys=${challenge-public-keys-json}"
            "--domain-allowed-networks=${domain-allowed-networks-json}"
            "--record-lifespan=${toString cfg.record-lifespan}"
          ] ++ (optional cfg.verbose "--verbose")));

        ExecStartPre = [
          ("+${pkgs.writeShellScript "nexus-wait-for-secrets.sh" ''
            until [ -f ${cfg.database.password-file} ]; do
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
    };

  };
}
