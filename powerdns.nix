{ config, lib, pkgs, ... }@toplevel:

with lib;
let
  cfg = config.nexus.dns-server;

  db-cfg = config.nexus.database;

  gpgsql-template = { host, database, user, enable-dnssec, debug ? false, ... }:
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

  pdns-config = { listen-addrs, port, subconfig-dir, ... }:
    pkgs.writeTextDir "pdns.conf" ''
      local-address=${concatStringsSep ", " listen-addresses}
      local-port=${toString port}
      launch=
      include-dir=${subconfig-dir}
    '';

  make-pgpass-file = user: target-file:
    let db = cfg.database;
    in pkgs.writeShellScript "genenrate-pgpass-file.sh" ''
      touch ${target-file}
      chown ${user} ${target-file}
      chmod 700 ${target-file}
      PASSWORD=$(cat ${db.password-file})
      echo "${db.host}:${
        toString db.port
      }:${db.database}:${db.user}:__PASSWORD__" | sed "s/__PASSWORD__/$PASSWORD/" > ${target-file}
    '';

  mkRecord = name: type: content: { inherit name type content; };

  insertOrUpdate = domain: record:
    let
      selectClause = concatStringsSep " " [
        "SELECT *"
        "FROM domains, records"
        "WHERE"
        "records.name='${record.name}'"
        "AND"
        "records.type='${record.type}'"
        "AND"
        "records.domain_id=domain.id"
        "AND"
        "domain.name='${domain}'"
      ];
      updateClause = concatStringsSep " " [
        "UPDATE records"
        "SET content='${record.content}'"
        "WHERE"
        "records.name='${record.name}'"
        "AND"
        "records.type='${record.type}'"
        "AND"
        "records.domain_id=(SELECT id FROM domain WHERE name='${domain}')"
      ];
      insertClause = concatStringsSep " " [
        "INSERT INTO records (domain_id, name, type, content)"
        "SELECT"
        "domain.id,"
        "'${records.name}',"
        "'${records.type}',"
        "'${records.content}'"
        "FROM domains"
        "WHERE"
        "domain.name='${domain}'"
      ];
    in ''
      IF EXISTS (${selectClause});
        ${updateClause}
      ELSE
        ${insertClause}
      END IF;
    '';

  initialize-domain-sql = domain:
    let
      domain-name = domain.domain-name;
      host-ip = pkgs.lib.network.host-ipv4 config hostname;
      ipv6-net = net: (builtins.match ":" net) != null;
      ipv4-net = net: !(ipv6-net net);
      domain-records = [
        (mkRecord domain-name "SOA"
          "ns1.${domain-name} hostmaster.${domain-name} ${
            toString config.instance.build-timestamp
          } 10800 3600 1209600 3600")
        (mkRecord "_dmark.${domain-name}" "TXT" ''
          "v=DMARC1; p=reject; rua=mailto:${domain.admin}; ruf=mailto:${domain.admin}; fo=1;"'')
        (mkRecord domain-name "NS" "ns1.${domain-name}")
        (mkRecord domain-name "TXT" (let
          networks = config.instance.local-networks;
          v4-nets = map (net: "ip4:${net}") (filter ipv4-net networks);
          v6-nets = map (net: "ip6:${net}") (filter ipv6-net networks);
          networks-string = concatStringsSep " " (v4-nets ++ v6-nets);
        in ''"v=spf1 mx ${networks-string} -all"''))
        (mkRecord "ns1.${domain-name}" "A" host-ip)
        (mkRecord domain-name "A" host-ip)
      ] ++ (optional (domain.gssapi-realm != null)
        (mkRecord "_kerberos.${domain-name}" "TXT" ''"domain.gssapi-realm"''))
        ++ (mapAttrsToList (alias: target: mkRecord alias "CNAME" target)
          domain.aliases);
      records-clauses = map insertOrUpdate domain-records;
    in pkgs.writeText "initialize-${domain-name}.sql" ''
      BEGIN
      INSERT INTO domains (name, master, type, notified_serial) VALUES ('${domain-name}', '${host-ip}', 'MASTER', '${
        toString config.instance.build-timestamp
      }') WHERE NOT EXISTS (SELECT * FROM domains WHERE name='${domain}');
      ${concatStringsSep "\n" records-strings}
      COMMIT;
    '';

  initialize-domain-script = domain:
    let domain-name = domain.domain-name;
    in pkgs.writeShellScript "initialize-${domain-name}.sh" ''
      if [ "$( psql -tAc "SELECT id FROM domains WHERE name='${domain-name}'" )" ]; then
        logger "${domain-name} already initialized, skipping"
        exit 0
      else
        logger "initializing ${domain-name} in powerdns database"
        psql -f ${initialize-domain-sql domain}
      fi
    '';

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    networking.firewall = {
      allowedTCPPorts = [ cfg.port ];
      allowedUDPPorts = [ cfg.port ];
    };

    systemd = let
      initialize-jobs = mapAttrs' (_: domainOpts:
        let domain-name = domainOpts.domain-name;
        in nameValuePair "powerdns-initialize-${domain-name}" {
          description = "Initialize the ${domain-name} domain";
          requires = [
            "powerdns-initialize-db.service"
            "powerdns-generate-pgpass.service"
          ];
          after = [
            "powerdns-initialize-db.service"
            "powerdns-generate-pgpass.service"
          ];
          requiredBy = [ "powerdns.service" ];
          wantedBy = [ "powerdns.service" ];
          before = [ "powerdns.service" ];
          environment = {
            PGHOST = cfg.database.host;
            PGUSER = cfg.database.user;
            PGDATABASE = db-cfg.database;
            PGPORT = toString db-cfg.port;
            PGSSLMODE = "require";
            PGPASSFILE = pgpass-file;
          };
          path = with pkgs; [ postgresql util-linux ];
          serviceConfig = { ExecStart = initialize-domain-script domainOpts; };
        }) config.nexus.domains;
    in {
      services = initialize-jobs // {

        powerdns-initialize-db = let pgpass-file = "$RUNTIME_DIRECTORY/pgpass";
        in {
          description = "Initialize the powerdns database.";
          requiredBy = [ "powerdns.service" ];
          before = [ "powerdns.service" ];
          requires = [ "powerdns-generate-pgpass.service" ];
          after = [ "powerdns-generate-pgpass.service" ];
          path = with pkgs; [ postgresql util-linux ];
          environment = {
            PGHOST = db-cfg.host;
            PGUSER = db-cfg.user;
            PGDATABASE = db-cfg.database;
            PGPORT = toString db-cfg.port;
            PGSSLMODE = "require";
            PGPASSFILE = pgpass-file;
          };
          serviceConfig = {
            ExecStartPre = let
              initPgpass =
                make-pgpass-file cfg.user "$RUNTIME_DIRECTORY/pgpass";
              ncCmd =
                "${pkgs.netcat}/bin/nc -z ${cfg.database.host} ${cfg.database.port}";
              pgWaitCmd =
                "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'";
            in pkgs.writeShellScript "powerdns-initialize-db-prep.sh" ''
              ${initPgpass}
              ${pgWaitCmd}
            '';
            ExecStart = pkgs.writeShellScript "powerdns-initialize-db.sh" ''
              HOME=$RUNTIME_DIRECTORY
              if [ "$( psql -tAc "SELECT to_regclass('public.domains')" )" ]; then
                logger "database initialized, skipping"
              else
                logger "initializing powerdns database"
                psql -f ${pkgs.powerdns}/share/doc/pdns/schema.pgsql.sql
              fi
            '';
          };
        };

        nexus-powerdns = {
          description = "Nexus PowerDNS server.";
          after = [ "network-online.target" ];
          wantedBy = [ "multi-user.target" ];
          path = with pkgs; [ powerdns postgresql util-linux ];
          serviceConfig = let module-directory = "$RUNTIME_DIRECTORY/modules";
          in {
            ExecStartPre = pkgs.writeShellScript "powerdns-init-config.sh"
              (concatStringsSep "\n" [
                ''MOD_DIR="${module-directory}"''
                ''mkdir -p "${module-directory}"''
                ''touch "${module-directory}/gpgsql.conf"''
                ''chown "$USER" "${module-directory}"''
                ''chmod 700 "${module-directory}"''
                ''chown "$USER" "${module-directory}/gpgsql.conf"''
                ''chmod 600 "${module-directory}/gpgsql.conf"''
                "PASSWORD=$(cat $CREDENTIALS_DIRECTORY/db.passwd)"
                ''
                  sed -e "s/__PASSWORD__/$PASSWORD/" > "${module-directory}/gpgsql.conf"''
              ]);
            ExecStart = concatStringsSep " " [
              "${pkgs.powerdns}/bin/pdns_server"
              "--daemon=no"
              "--guardian=yes"
              ''--config-dir="${module-directory}"''
            ];
            ExecStartPost = let
              signDomain = domain: ''
                DNSINFO=$(${pkgs.powerdns}/bin/pdnsutil --config-dir=${module-directory} show-zone ${domain})
                if [[ "x$DNSINFO" =~ "xNo such zone in the database" ]]; then
                  logger "zone ${domain} does not exist in powerdns database"
                elif [[ "x$DNSINFO" =~ "xZone is not actively secured" ]]; then
                  logger "securing zone ${domain} in powerdns database"
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=${module-directory} secure-zone ${domain}
                elif [[ "x$DNSINFO" =~ "xNo keys for zone" ]]; then
                  logger "securing zone ${domain} in powerdns database"
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=${module-directory} secure-zone ${domain}
                else
                  logger "not securing zone ${domain} in powerdns database"
                fi
                ${pkgs.powerdns}/bin/pdnsutil --config-dir=${module-directory} rectify-zone ${domain}
              '';
            in pkgs.writeShellScript "nexus-powerdns-secure-zones.sh"
            (concatStringsSep "\n"
              (map signDomain (attrNames config.nexus.domains)));
            RuntimeDirectory = "nexus-powerdns";
          };
        };
      };
    };
  };
}
