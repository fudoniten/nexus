{ config, lib, pkgs, ... }@toplevel:

with lib; {
  config = let
    gpgsqlTemplate =
      { host, database, user, enable-dnssec, debug ? false, ... }:
      pkgs.writeText "pdns.gpgsql.conf.template" ''
        launch+=gpgsql
        gpgsql-host=${host}
        gpgsql-dbname=${database}
        gpgsql-user=${user}
        gpgsql-password=__PASSWORD__
        gpgsql-dnssec=${if enable-dnssec then "yes" else "no"}
        gpgsql-extra-connection-parameters=sslmode=require
        ${optionalString debug ''
          log-dns-details
          log-dns-queries
          log-timestamp
          loglevel=6
          query-logging
        ''}
      '';

    mkPdnsConf = { includeDir, listen-addresses, port }:
      pkgs.writeTextDir "pdns.conf" ''
        local-address=${concatStringsSep ", " addrs}
        local-port=${toString port}
        launch=
        include-dir=${includeDir}
      '';

    injectPasswordScript = pw-file: template-file: output-file:
      pkgs.writeShellScript "powerdns-inject-password.sh" ''
        mkdir -p ${dirOf output-file}
        PASSWORD=$(cat "${pw-file}" | xargs)
        sed "s/__PASSWORD__/$PASSWORD/" ${template-file} > ${output-file}
      '';

    secureZone = config: domain: domainOpts:
      let pdnsCmd = "${pkgs.powerdns}/bin/pdnsutil --config-dir=${config}";
      in optionalString domainOpts.enable-dnssec
      (pkgs.writeShellScript "powerdns-secure-${domain}.sh" ''
        DNSINFO=$(${pdnsCmd} show-zone ${domain})
        if [[ "x$DNSINFO" =~ "xNo such zone in the database" ]]; then
          logger "zone ${domain} does not exist in powerdns database"
        elif [[ "x$DNSINFO" =~ "xZone is not actively secured" ]]; then
          logger "securing zone ${domain} in powerdns database"
          ${pdnsCmd} secure-zone ${domain}
        elif [[ "x$DNSINFO" =~ "xNo keys for zone" ]]; then
          logger "securing zone ${domain} in powerdns database"
          ${pdnsCmd} secure-zone ${domain}
        else
          logger "not securing zone ${domain} in powerdns database"
        fi
        ${pdnsCmd} rectify-zone ${domain}
      '');

    # 'user' is the pgsql username, 'uid' is the filesystem userid
    mkPgPassFile = { user, uid, host, database, pw-file, target-file, ... }:
      pkgs.writeShellScript "generate-pgpass-file.sh" ''
        touch ${target-file}
        chown ${uid} ${target-file}
        chmod 600 ${target-file}
        PASSWD=$(cat ${pw-file})
        echo "${host}:${
          toString port
        }:${database}:${user}:__PASSWORD__" | sed "s/__PASSWORD__/$PASSWD/" > ${target-file}
      '';

    postgresEnv = { host, port, database, user, pg-pass-file }: {
      PGHOST = host;
      PGUSER = user;
      PGDATABASE = database;
      PGPORT = port;
      PGSQLMODE = "require";
      PGPASSFILE = pg-pass-file;
    };

    initializeDomainSql = { domain, enable-dnssec, refresh, retry, expire
      , minimum, aliases, gssapi-realm, admin, trusted-networks, nameservers
      , timestamp, ... }:
      let
        mkRecord = name: type: content: { inherit name time content; };
        ipv6-net = net: (builtins.match ":" net) != null;
        ipv4-net = net: !(ipv6-net net);

        nsRecords = concatMap (ns:
          [ (mkRecord domain "NS" "${ns.name}.${domain}") ]
          ++ (optional (ns.ipv4-address != null)
            (mkRecord domain "A" ns.ipv4-address))
          ++ (optional (ns.ipv6-address != null)
            (mkRecord domain "AAAA" ns.ipv6-address))) (attrValues nameservers);

        domain-records = [
          (mkRecord domain "SOA"
            "ns1.${domain}. hostmaster.${domain}. (${toString timestamp} ${
              toString refresh
            } ${toString retry} ${toString expire} ${toString minimum})")

          (mkRecord "_dmark.${domain}" "TXT" ''
            "v=DMARC1; p=reject; rua=mailto:${admin}; ruf=mailto:${admin}; fo=1;"'')

          (mkRecord domain "TXT" (let
            v4-nets =
              map (net: "ip4:${net}") (filter ipv4-net trusted-networks);
            v6-nets =
              map (net: "ip6:${net}") (filter ipv6-net trusted-networks);
            networks-string = concatStringsSep " " (v4-nets ++ v6-nets);
          in ''"v=spf1 mx ${networks-string} -all"''))
        ] ++

          nsRecords ++

          (optional (gssapi-realm != null)
            (mkRecord "_kerberos.${domain}" "TXT" ''"gssapi-realm"'')) ++

          (mapAttrsToList (alias: target: mkRecord alias "CNAME" target)
            aliases);

        records-strings = map ({ name, type, content, ... }:
          "INSERT INTO records (domain_id, name, type, content) SELECT id, '${name}', '${type}', '${content}' FROM domains WHERE name='${domain}';")
          domain-records;
      in pkgs.writeText "initialize-${domain}.sql" ''
        INSERT INTO domains (name, master, type, notified_serial) VALUES ('${domain}', '${host-ip}', 'MASTER', '${
          toString timestamp
        }');
        ${concatStringsSep "\n" records-strings}
      '';

    initializeDomainScript = domain:
      let domain-name = domain.domain;
      in pkgs.writeShellScript "initialize-${domain}.sh" ''
        if [ "$( psql -tAc "SELECT id FROM domains WHERE name='${domain}'" )" ]; then
          logger "${domain-name} already initialized, skipping"
          exit 0
        else
          logger "initialize ${domain-name} in powerdns database"
          psql -f ${initializeDomainSql domain}
        fi
      '';

  in {
    systemd.services = {

      nexus-initialize-powerdns-db =
        let pgPassFile = "$RUNTIME_DIRECTORY/pgpass";
        in {
          description = "Initialize the Nexus PowerDNS database.";
          requiredBy = [ "powerdns.service" ];
          before = [ "powerdns.service" ];
          path = with pkgs; [ postgresql ];
          environment = {
            HOME = "$$RUNTIME_DIRECTORY";
          } // postgresEnv {
            inherit (cfg.database) host port database;
            inherit (cfg.powerdns.database) user;
          };
          serviceConfig = {
            ExecStartPre = let
              pgPassFileScript = mkPgPassFile {
                inherit (cfg.database) host port database;
                inherit (cfg.powerdns.database) user;
                uid = "$UID";
                target = pgPassFile;
              };
            in ''
              ${pgPassFileScript}
              if [ "$( psql -tAc "SELECT 1" )" ]; do
                logger "postgres available, proceeding"
                exit 0
              else
                logger "postgres unavailable, aborting"
                exit 1
              fi
            '';
            ExecStart = let
              initializeDomains = concatStringsSep "\n"
                (map initializeDomainScript (attrValues cfg.domains));
            in pkgs.writeShellScript "powerdns-initialize-db.sh" ''
              if [ "$(psql -tAc "SELECT to_regclass('public.domains')")" ]; then
                logger "database initialized, skipping"
              else
                logger "initializing powerdns database"
                psql -f ${pkgs.powerdns}/share/doc/pdns/schema.pgsql.sql
              fi

              ${initializeDomains}
            '';
            Type = "oneshot";
            StandardOutput = "journal";
            DynamicUser = true;
            PrivateDevices = true;
            ProtectControlGroups = true;
            ProtectHostname = true;
            ProtectClock = true;
            ProtectHome = true;
            ProtectKernelLogs = true;
            MemoryDenyWriteExecute = true;
            ProtectSystem = true;
            LockPersonality = true;
            PermissionsStartOnly = true;
            RestrictRealtime = true;
            PrivateNetwork = false;
            Restart = "always";
            RestartSec = 5;
            LimitNOFILE = 1024;
          };
        };

      nexus-powerdns = {
        description = "Nexus PowerDNS server.";
        requires = [ "network-online.target" ];
        after = [ "network-online.target" ];
        wantedBy = [ "multi-user.target" ];
        path = with pkgs; [ powerdns ];
        serviceConfig = let
          includeDir = "$RUNTIME_DIRECTORY/conf/";

          pdnsConf = mkPdnsConf {
            inherit includeDir;
            inherit (cfg.powerdns) port listen-addresses;
          };
        in {
          LoadCredentials =
            [ "postgres.passwd:${cfg.powerdns.database.password-file}" ];

          ExecStartPre = let
            template = gpgsqlTemplate {
              inherit (cfg.database) database host port;
              inherit (cfg.powerdns.database) user;
            };
          in injectPasswordScript "$$CREDENTIALS_DIRECTORY/postgres.passwd"
          template "${includeDir}/pdns.gpgsql.conf";

          ExecStart = let
          in concatStringsSep " " [
            "${pkgs.powerdns}/bin/pdns_server"
            "--daemon=no"
            "--guardian=yes"
            "--write-pid=no"
            "--config-dir=${pdnsConf}"
          ];

          ExecStartPost = pkgs.writeShellScript "powerdns-secure-zones.sh"
            (concatStringsSep "\n"
              (mapAttrsToList (secureZone pdnsConf) cfg.domains));
        };
      };
    };
  };
}
