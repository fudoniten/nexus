{ config, lib, pkgs, ... }@toplevel:

with lib;
let
  cfg = config.nexus.dns-server;

  db-cfg = config.nexus.database;

  genGpgsqlConfig = gpgsql-target:
    { db-host, db-user, db-password-file, database, enable-dnssec, debug ? false
    , ... }:
    let
      template = pkgs.writeText "pdns.gpgsql.conf.template" ''
        launch+=gpgsql
        gpgsql-host=${db-host}
        gpgsql-dbname=${database}
        gpgsql-user=${db-user}
        gpgsql-password=__PASSWORD__
        gpgsql-dnssec=${if enable-dnssec then "yes" else "no"}
        ${optionalString debug ''
          log-dns-details
          log-dns-queries
          log-timestamp
          loglevel=6
          query-logging
        ''}
      '';
    in ''
      mkdir -p $(dirname ${gpgsql-target})
      PASSWD=$(cat ${db-password-file})
      sed "s/__PASSWORD__/$PASSWD/" ${template} > ${gpgsql-target}
      cat ${gpgsql-target}
    '';

  genPdnsConfig =
    { target-dir, listen-addresses, port, secondary-servers, ... }@config:
    let
      target = "${target-dir}/pdns.conf";
      gpgsql-target = "${target-dir}/modules/gpgsql.conf";
      baseCfg = let
        secondary-server-str = concatStringsSep "," secondary-servers;
        secondary-clause = optionalString (secondary-servers != [ ]) ''
          allow-axfr-ips=${secondary-server-str}
        '';
      in pkgs.writeText "pdns.conf.template" (''
        local-address=${concatStringsSep ", " listen-addresses}
        local-port=${toString port}
        primary=yes
        launch=
      '' + secondary-clause);
      moduleDirectory = "${target-dir}/modules";
      genGpgsqlConfScript =
        genGpgsqlConfig "${moduleDirectory}/gpgsql.conf" config;
    in pkgs.writeShellScript "gen-pdns-config.sh" ''
      mkdir -p ${target-dir}
      touch ${target-dir}/pdns.conf
      cat ${baseCfg} > ${target-dir}/pdns.conf
      echo "include-dir=${moduleDirectory}" >> ${target-dir}/pdns.conf
      ${genGpgsqlConfScript}
    '';

  pdns-config = { listen-addrs, port, subconfig-dir, ... }:
    pkgs.writeTextDir "pdns.conf" ''
      local-address=${concatStringsSep ", " listen-addresses}
      local-port=${toString port}
      launch=
      include-dir=${subconfig-dir}
    '';

  mkRecord = name: type: content: { inherit name type content; };

  insertOrUpdate = domain: record:
    let
      selectClause = concatStringsSep " " [
        "SELECT * FROM domains, records WHERE"
        "records.name='${record.name}'"
        "AND"
        "records.type='${record.type}'"
        "AND"
        "records.domain_id=domains.id"
        "AND"
        "domains.name='${domain}'"
      ];
      updateClause = concatStringsSep " " [
        "UPDATE records SET"
        "content='${record.content}'"
        "WHERE"
        "records.name='${record.name}'"
        "AND"
        "records.type='${record.type}'"
        "AND"
        "records.domain_id=(SELECT id FROM domains WHERE name='${domain}')"
      ];
      insertClause = concatStringsSep " " [
        "INSERT INTO records (domain_id, name, type, content)"
        "SELECT"
        "domains.id,"
        "'${record.name}',"
        "'${record.type}',"
        "'${record.content}'"
        "FROM domains"
        "WHERE"
        "domains.name='${domain}'"
      ];
    in ''
      IF EXISTS (${selectClause}) THEN
        ${updateClause};
      ELSE
        ${insertClause};
      END IF;
    '';

  ensureExists = domain: record:
    let
      selectClause = concatStringsSep " " [
        "SELECT * FROM domains, records WHERE"
        "records.name='${record.name}'"
        "AND"
        "records.type='${record.type}'"
        "AND"
        "records.content='${record.content}'"
        "AND"
        "records.domain_id=domains.id"
        "AND"
        "domains.name='${domain}'"
      ];
      insertClause = concatStringsSep " " [
        "INSERT INTO records (domain_id, name, type, content)"
        "SELECT"
        "domains.id,"
        "'${record.name}',"
        "'${record.type}',"
        "'${record.content}'"
        "FROM domains"
        "WHERE"
        "domains.name='${domain}'"
      ];
    in ''
      IF NOT EXISTS (${selectClause}) THEN
        ${insertClause}
      END IF;
    '';

  ensureChallengeTable = concatStringsSep " " [
    "CREATE TABLE IF NOT EXISTS challenges"
    "("
    "domain_id INTEGER NOT NULL,"
    "challenge_id UUID NOT NULL,"
    "hostname VARCHAR(255) NOT NULL,"
    "created_at TIMESTAMP DEFAULT NOW(),"
    "record_id BIGINT NOT NULL,"
    "active BOOLEAN DEFAULT TRUE,"
    "PRIMARY KEY(domain_id, challenge_id)"
    ");"
  ];

  mapConcatAttrsToList = f: as: concatLists (mapAttrsToList f as);

  initializeDomainSql = domain:
    let
      inherit (domain) domain-name;
      ipv6-net = net: (builtins.match ":" net) != null;
      ipv4-net = net: !(ipv6-net net);
      # NOTE: the actual NS records are below, they don't work here because
      #       the name/type are not unique
      ns-records = concatMap (nsOpts:
        (optional (nsOpts.ipv4-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "A" nsOpts.ipv4-address))
        ++ (optional (nsOpts.ipv6-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "AAAA"
            nsOpts.ipv6-address))) (attrValues domain.nameservers);

      primaryNameserver = head (attrValues domain.nameservers);

      domain-records = [
        (mkRecord domain-name "SOA"
          "${primaryNameserver.name}.${domain-name} hostmaster.${domain-name} ${
            toString config.instance.build-timestamp
          } 10800 3600 1209600 3600")
        (mkRecord "_dmark.${domain-name}" "TXT" ''
          "v=DMARC1; p=reject; rua=mailto:${domain.admin}; ruf=mailto:${domain.admin}; fo=1;"'')
        (mkRecord domain-name "TXT" (let
          networks = domain.trusted-networks;
          v4-nets = map (net: "ip4:${net}") (filter ipv4-net networks);
          v6-nets = map (net: "ip6:${net}") (filter ipv6-net networks);
          networks-string = concatStringsSep " " (v4-nets ++ v6-nets);
        in ''"v=spf1 mx ${networks-string} -all"''))
      ] ++ (optional (domain.gssapi-realm != null)
        (mkRecord "_kerberos.${domain-name}" "TXT" "${domain.gssapi-realm}"))
        ++ (mapAttrsToList
          (alias: target: mkRecord "${alias}.${domain-name}" "CNAME" target)
          domain.aliases) ++ domain.records ++ ns-records;
      records-clauses = map (insertOrUpdate domain-name) domain-records;
      ns-clauses = map (ensureExists domain-name)
        (map (nsOpts: mkRecord domain-name "NS" "${nsOpts.name}.${domain-name}")
          (attrValues domain.nameservers));
    in ''
      DO $$
      BEGIN
      ${ensureChallengeTable}
      INSERT INTO domains (name, master, type, notified_serial) SELECT '${domain-name}', '${primaryNameserver.ipv4-address}', 'MASTER', '${
        toString config.instance.build-timestamp
      }' WHERE NOT EXISTS (SELECT * FROM domains WHERE name='${domain-name}');
      ${concatStringsSep "\n" records-clauses}
      END;
      $$
    '';

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    networking.firewall = {
      allowedTCPPorts = [ cfg.port ];
      allowedUDPPorts = [ cfg.port ];
    };

    systemd = {
      services = {
        nexus-powerdns-initialize-db = let
          pgpassFile = "$RUNTIME_DIRECTORY/.pgpass";
          mkPgpassFile = pkgs.writeShellScript "generate-pgpass-file.sh" ''
            touch ${pgpassFile}
            chmod 600 ${pgpassFile}
            PASSWORD=$(cat $CREDENTIALS_DIRECTORY/db.passwd)
            echo "${db-cfg.host}:${
              toString db-cfg.port
            }:${db-cfg.database}:${cfg.database.user}:__PASSWORD__" | sed "s/__PASSWORD__/$PASSWORD/" > ${pgpassFile}
          '';
        in {
          description = "Initialize the powerdns database.";
          requiredBy = [ "nexus-powerdns.service" ];
          before = [ "nexus-powerdns.service" ];
          path = with pkgs; [ postgresql util-linux ];
          environment = {
            PGHOST = db-cfg.host;
            PGDATABASE = db-cfg.database;
            PGPORT = toString db-cfg.port;
            PGUSER = cfg.database.user;
            ## Only running on localhost for now
            # PGSSLMODE = "require";
          };
          serviceConfig = {
            ExecStartPre = let
              ncCmd = "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${
                  toString db-cfg.port
                }";
              pgWaitCmd =
                "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'";
            in pkgs.writeShellScript "powerdns-initialize-db-prep.sh" ''
              ${pgWaitCmd}
            '';
            ExecStart = let
              initDomainSqlFile = domainOpts:
                pkgs.writeText "init-${domainOpts.domain-name}.sql"
                (initializeDomainSql domainOpts);
              domainInitScript = _: domainOpts: ''
                psql -U ${cfg.database.user} --dbname=${db-cfg.database} -f ${
                  initDomainSqlFile domainOpts
                }
              '';
              domainInitScripts = concatStringsSep "\n"
                (mapAttrsToList domainInitScript config.nexus.domains);
            in pkgs.writeShellScript "powerdns-initialize-db.sh" ''
              ${mkPgpassFile}
              export HOME=$RUNTIME_DIRECTORY
              export PGPASSFILE=${pgpassFile}

              if [ "$( psql --dbname=${db-cfg.database} -U ${cfg.database.user} -tAc "SELECT to_regclass('public.domains')" )" ]; then
                echo "database initialized, skipping"
              else
                echo "initializing powerdns database"
                psql --dbname=${db-cfg.database} -U ${cfg.database.user} -f ${pkgs.powerdns}/share/doc/pdns/schema.pgsql.sql
              fi
              ${domainInitScripts}
            '';
            RuntimeDirectory = "nexus-powerdns-initialize-db";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            DynamicUser = true;
          };
          unitConfig.ConditionPathExists = [ cfg.database.password-file ];
        };

        nexus-powerdns = {
          description = "Nexus PowerDNS server.";
          after =
            [ "network-online.target" "nexus-powerdns-initialize-db.service" ];
          wantedBy = [ "multi-user.target" ];
          requires = [ "nexus-powerdns-initialize-db.service" ];
          path = with pkgs; [ powerdns postgresql util-linux ];
          serviceConfig = let module-directory = "$RUNTIME_DIRECTORY/modules";
          in {
            ExecStartPre = let
              ncCmd = "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${
                  toString db-cfg.port
                }";
            in pkgs.writeShellScript "powerdns-initialize-db-prep.sh"
            "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'";
            ExecStart = let
              genConfig = genPdnsConfig {
                target-dir = "$RUNTIME_DIRECTORY";
                inherit (cfg)
                  port listen-addresses secondary-servers debug enable-dnssec;
                inherit (config.nexus.database) database;
                db-host = config.nexus.database.host;
                db-user = cfg.database.user;
                db-password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
              };
              secureZones = let
                signDomain = domain: ''
                  cat $RUNTIME_DIRECTORY/pdns.conf
                  cat $RUNTIME_DIRECTORY/modules/gpgsql.conf
                  DNSINFO=$(${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY show-zone ${domain})
                  echo "DNS INFO: $DNSINFO"
                  if [[ "$DNSINFO" =~ "No such zone in the database" ]]; then
                    echo "zone ${domain} does not exist in powerdns database"
                    logger "zone ${domain} does not exist in powerdns database"
                  elif [[ "$DNSINFO" =~ "Zone is not actively secured" ]]; then
                    echo "securing zone ${domain} in powerdns database"
                    logger "securing zone ${domain} in powerdns database"
                    ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY secure-zone ${domain}
                  elif [[ "$DNSINFO" =~ "No keys for zone" ]]; then
                    echo "securing zone ${domain} in powerdns database"
                    logger "securing zone ${domain} in powerdns database"
                    ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY secure-zone ${domain}
                  else
                    echo "not securing zone ${domain} in powerdns database"
                    logger "not securing zone ${domain} in powerdns database"
                  fi
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY rectify-zone ${domain}
                '';
              in pkgs.writeShellScript "nexus-powerdns-secure-zones.sh" ''
                export HOME=$RUNTIME_DIRECTORY
                ${concatStringsSep "\n"
                (map signDomain (attrNames config.nexus.domains))}
              '';
              launchCmd = concatStringsSep " " ([
                "${pkgs.powerdns}/bin/pdns_server"
                "--daemon=no"
                "--guardian=yes"
                ''--config-dir="$RUNTIME_DIRECTORY"''
              ] ++ (optionals cfg.debug [
                "--log-dns-queries=yes"
                "--loglevel=7"
              ]));
            in pkgs.writeShellScript "nexus-powerdns-start.sh" ''
              ${genConfig}
              ${secureZones}
              ${launchCmd}
            '';
            RuntimeDirectory = "nexus-powerdns";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            TimeoutStartSec = "180";
          };
          unitConfig.ConditionPathExists = [ cfg.database.password-file ];
        };
      };
    };
  };
}
