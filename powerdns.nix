{ config, lib, pkgs, ... }@toplevel:

with lib;
let
  cfg = config.nexus.dns-server;

  target-gpgsql-config = "${runtime-dir}/pdns.local.gpgsql.conf";

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

  initialize-domain-sql = domain:
    let
      domain-name = domain.domain;
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
      records-strings = map (record:
        "INSERT INTO records (domain_id, name, type, content) SELECT id, '${record.name}', '${record.type}', '${record.content}' FROM domains WHERE name='${domain-name}';")
        domain-records;
    in pkgs.writeText "initialize-${domain-name}.sql" ''
      INSERT INTO domains (name, master, type, notified_serial) VALUES ('${domain-name}', '${host-ip}', 'MASTER', '${
        toString config.instance.build-timestamp
      }');
      ${concatStringsSep "\n" records-strings}
    '';

  initialize-domain-script = domain:
    let domain-name = domain.domain;
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
      pgpass-file = "${runtime-dir}/pgpass";

      initialize-jobs = mapAttrs' (_: domain:
        let domain-name = domain.domain;
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
            PGDATABASE = cfg.database.database;
            PGPORT = toString cfg.database.port;
            PGSSLMODE = "require";
            PGPASSFILE = pgpass-file;
          };
          path = with pkgs; [ postgresql util-linux ];
          serviceConfig = { ExecStart = initialize-domain-script domain; };
        }) cfg.domains;
    in {
      tmpfiles.rules = [ "d ${runtime-dir} 0750 ${cfg.user} ${cfg.group} - -" ];

      services = initialize-jobs // {
        powerdns-config-generator = {
          description = "Generate PostgreSQL config for backplane DNS server.";
          type = "oneshot";
          restartIfChanged = true;
          readWritePaths = [ runtime-dir ];
          user = cfg.user;
          execStart = let
            script = pkgs.writeShellScript "generate-powerdns-config.sh" ''
              TARGET=${target-gpgsql-config}
              touch $TARGET
              chown ${cfg.user}:${cfg.group} $TARGET
              chmod 0700 $TARGET
              PASSWORD=$( cat ${cfg.database.password-file} | tr -d '\n')
              sed -e 's/__PASSWORD__/$PASSWORD/' ${gpgsql-template} > $TARGET
            '';
          in "${script}";
        };

        powerdns-generate-pgpass = {
          description = "Create pgpass file required for database init.";
          serviceConfig = {
            ExecStart = make-pgpass-file cfg.user "${runtime-dir}/pgpass";
          };
        };

        powerdns-initialize-db = {
          description = "Initialize the powerdns database.";
          requiredBy = [ "powerdns.service" ];
          before = [ "powerdns.service" ];
          requires = [ "powerdns-generate-pgpass.service" ];
          after = [ "powerdns-generate-pgpass.service" ];
          path = with pkgs; [ postgresql util-linux ];
          environment = {
            PGHOST = cfg.database.host;
            PGUSER = cfg.database.user;
            PGDATABASE = cfg.database.database;
            PGPORT = toString cfg.database.port;
            PGSSLMODE = "require";
            PGPASSFILE = pgpass-file;
          };
          serviceConfig = {
            ExecStart = pkgs.writeShellScript "powerdns-initialize-db.sh" ''
              if [ "$( psql -tAc "SELECT to_regclass('public.domains')" )" ]; then
                logger "database initialized, skipping"
              else
                logger "initializing powerdns database"
                psql -f ${pkgs.powerdns}/share/doc/pdns/schema.pgsql.sql
              fi
            '';
            ## Doesn't seem to use env vars, and so fails if remote
            ## Wait until posgresql is available before starting
            # ExecStartPre =
            #   pkgs.writeShellScript "ensure-postgresql-running.sh" ''
            #     while [ ! "$( psql -tAc "SELECT 1" )" ]; do
            #       ${pkgs.coreutils}/bin/sleep 3
            #     done
            #   '';
          };
        };

        nexus-powerdns = {
          description = "Nexus PowerDNS server.";
          after = [ "network-online.target" ];
          wantedBy = [ "multi-user.target" ];
          path = with pkgs; [ powerdns postgresql ];
          serviceConfig = {
            ExecStart = concatStringsSep " " [
              "${pkgs.powerdns}/bin/pdns_server"
              "--daemon=no"
              "--guardian=yes"
              "--config-dir=FIXME"
            ];
            ExecStartPost = let domain = nexus-cfg.domain;
            in pkgs.writeShellScript "nexus-powerdns-secure-zones.sh" ''
              DNSINFO=$(${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} show-zone ${domain})
              if [[ "x$DNSINFO" =~ "xNo such zone in the database" ]]; then
                logger "zone ${domain} does not exist in powerdns database"
              elif [[ "x$DNSINFO" =~ "xZone is not actively secured" ]]; then
                logger "securing zone ${domain} in powerdns database"
                ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} secure-zone ${domain}
              elif [[ "x$DNSINFO" =~ "xNo keys for zone" ]]; then
                logger "securing zone ${domain} in powerdns database"
                ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} secure-zone ${domain}
              else
                logger "not securing zone ${domain} in powerdns database"
              fi
              ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} rectify-zone ${domain}
            '';
          };
        };

        powerdns = {
          description = "PowerDNS nameserver.";
          requires = [ "powerdns-config-generator.service" ];
          after = [
            "network.target"
            "powerdns-config-generator.service"
            "postgresql.service"
          ];
          wantedBy = [ "multi-user.target" ];
          path = with pkgs; [ powerdns postgresql util-linux ];
          serviceConfig = {
            ExecStartPre = pkgs.writeShellScript "powerdns-init-config.sh" ''
              TARGET=${target-gpgsql-config}
              touch $TARGET
              chown ${cfg.user}:${cfg.group} $TARGET
              chmod 0700 $TARGET
              PASSWORD=$( cat ${cfg.database.password-file} | tr -d '\n')
              sed -e "s/__PASSWORD__/$PASSWORD/" ${gpgsql-template} > $TARGET
            '';
            ExecStart = pkgs.writeShellScript "launch-powerdns.sh"
              (concatStringsSep " " [
                "${pkgs.powerdns}/bin/pdns_server"
                "--setuid=${cfg.user}"
                "--setgid=${cfg.group}"
                "--chroot=${runtime-dir}"
                "--daemon=no"
                "--guardian=no"
                "--write-pid=no"
                "--config-dir=${pdns-config-dir}"
              ]);
            ExecStartPost = pkgs.writeShellScript "powerdns-secure-zones.sh"
              (concatStringsSep "\n" (mapAttrsToList (_: domain: ''
                DNSINFO=$(${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} show-zone ${domain.domain})
                if [[ "x$DNSINFO" =~ "xNo such zone in the database" ]]; then
                  logger "zone ${domain.domain} does not exist in powerdns database"
                elif [[ "x$DNSINFO" =~ "xZone is not actively secured" ]]; then
                  logger "securing zone ${domain.domain} in powerdns database"
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} secure-zone ${domain.domain}
                elif [[ "x$DNSINFO" =~ "xNo keys for zone" ]]; then
                  logger "securing zone ${domain.domain} in powerdns database"
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} secure-zone ${domain.domain}
                else
                  logger "not securing zone ${domain.domain} in powerdns database"
                fi
                ${pkgs.powerdns}/bin/pdnsutil --config-dir=${pdns-config-dir} rectify-zone ${domain.domain}
              '') config.nexus.domains));
          };
        };
      };
    };
  };
}
