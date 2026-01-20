{ config, lib, pkgs, ... }@toplevel:

with lib;
let
  cfg = config.nexus.dns-server;

  db-cfg = config.nexus.database;

  # Generate PowerDNS PostgreSQL backend configuration
  # Takes a target path and database configuration, returns a shell script
  # that generates the gpgsql module config with password substitution
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
    '';

  # Generate complete PowerDNS configuration
  # Creates base config and includes the PostgreSQL backend module
  genPdnsConfig =
    { target-dir, listen-addresses, port, secondary-servers, ... }@config:
    let
      target = "${target-dir}/pdns.conf";
      gpgsql-target = "${target-dir}/modules/gpgsql.conf";
      baseCfg = let
        secondary-server-str = concatStringsSep "," secondary-servers;
        # Configure AXFR (zone transfer) permissions for secondary servers
        secondary-clause = optionalString (secondary-servers != [ ]) ''
          allow-axfr-ips=${secondary-server-str}
          also-notify=${secondary-server-str}
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

  # Legacy config generator (unused but kept for compatibility)
  pdns-config = { listen-addrs, port, subconfig-dir, ... }:
    pkgs.writeTextDir "pdns.conf" ''
      local-address=${concatStringsSep ", " listen-addresses}
      local-port=${toString port}
      launch=
      include-dir=${subconfig-dir}
    '';

  # Helper to create DNS record objects
  mkRecord = name: type: content: { inherit name type content; };

  # Generate SQL to insert or update a DNS record
  # Used during domain initialization to ensure records exist
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

  # Generate SQL to ensure a DNS record exists (insert only if not present)
  # Used for NS records which can have multiple entries with same name/type
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

  # Create the challenges table for ACME DNS-01 validation
  # Stores temporary TXT records for Let's Encrypt certificate validation
  ensureChallengeTable = concatStringsSep " " [
    "CREATE TABLE IF NOT EXISTS challenges"
    "("
    "domain_id INTEGER NOT NULL,"
    "challenge_id UUID NOT NULL,"
    "hostname VARCHAR(255) NOT NULL,"
    "created_at TIMESTAMP NOT NULL DEFAULT NOW(),"
    "record_id BIGINT NOT NULL,"
    "active BOOLEAN NOT NULL DEFAULT TRUE,"
    "PRIMARY KEY(domain_id, challenge_id)"
    ");"
  ];

  # Read the SQL trigger that auto-increments SOA serial on record changes
  createUpdateSerialTrigger =
    builtins.readFile ../sql/update-serial-trigger.sql;

  # Helper to concat mapped attributes into a list
  mapConcatAttrsToList = f: as: concatLists (mapAttrsToList f as);

  # Generate SQL to initialize a domain with all its records
  # Creates domain entry, SOA, NS, A/AAAA for nameservers, SPF, DMARC, aliases, etc.
  initializeDomainSql = domain:
    let
      inherit (domain) domain-name;
      ipv6-net = net: (builtins.match ":" net) != null;
      ipv4-net = net: !(ipv6-net net);

      # Create A/AAAA records for nameserver hosts
      # NOTE: NS records are created separately via ensureExists to handle duplicates
      ns-records = concatMap (nsOpts:
        (optional (nsOpts.ipv4-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "A" nsOpts.ipv4-address))
        ++ (optional (nsOpts.ipv6-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "AAAA"
            nsOpts.ipv6-address))) (attrValues domain.nameservers);

      primaryNameserver = head (attrValues domain.nameservers);

      # Build all domain records: SOA, DMARC, SPF, Kerberos, CNAMEs, custom records
      domain-records = [
        # SOA record with primary nameserver and admin contact
        (mkRecord domain-name "SOA"
          "${primaryNameserver.name}.${domain-name} hostmaster.${domain-name} ${
            toString config.instance.build-timestamp
          } 10800 3600 1209600 3600")
        # DMARC policy for email authentication
        (mkRecord "_dmark.${domain-name}" "TXT" ''
          "v=DMARC1; p=reject; rua=mailto:${domain.admin}; ruf=mailto:${domain.admin}; fo=1;"'')
        # SPF record defining authorized mail senders
        (mkRecord domain-name "TXT" (let
          networks = domain.trusted-networks;
          v4-nets = map (net: "ip4:${net}") (filter ipv4-net networks);
          v6-nets = map (net: "ip6:${net}") (filter ipv6-net networks);
          networks-string = concatStringsSep " " (v4-nets ++ v6-nets);
        in ''"v=spf1 mx ${networks-string} -all"''))
      ] ++ (optional (domain.gssapi-realm != null)
      # Kerberos realm TXT record if configured
        (mkRecord "_kerberos.${domain-name}" "TXT" "${domain.gssapi-realm}"))
        ++ (mapAttrsToList
          # CNAME aliases for the domain
          (alias: target: mkRecord "${alias}.${domain-name}" "CNAME" target)
          domain.aliases) ++ domain.records ++ ns-records;

      # Generate insert/update clauses for all records
      records-clauses = map (insertOrUpdate domain-name) domain-records;

      # Generate insert clauses for NS records (one per nameserver)
      ns-clauses = map (ensureExists domain-name)
        (map (nsOpts: mkRecord domain-name "NS" "${nsOpts.name}.${domain-name}")
          (attrValues domain.nameservers));
    in ''
      DO $$
      BEGIN
      ${ensureChallengeTable}
      ${createUpdateSerialTrigger}
      -- Insert domain if it doesn't exist
      INSERT INTO domains (name, master, type, notified_serial) SELECT '${domain-name}', '${primaryNameserver.ipv4-address}', 'MASTER', '${
        toString config.instance.build-timestamp
      }' WHERE NOT EXISTS (SELECT * FROM domains WHERE name='${domain-name}');
      ${concatStringsSep "\n" records-clauses}
      ${concatStringsSep "\n" ns-clauses}
      END;
      $$
    '';

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    # Open firewall ports for DNS (both TCP and UDP on port 53 by default)
    networking.firewall = {
      allowedTCPPorts = [ cfg.port ];
      allowedUDPPorts = [ cfg.port ];
    };

    systemd = {
      services = {
        # ============================================================================
        # Database Initialization Service
        # ============================================================================
        # Runs before PowerDNS starts to ensure the database schema and domains exist
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
          description =
            "Initialize the PowerDNS PostgreSQL database schema and domains";
          requiredBy = [ "nexus-powerdns.service" ];
          before = [ "nexus-powerdns.service" ];
          after = [ "network-online.target" ];
          requires = [ "network-online.target" ];
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
            # Wait for PostgreSQL to be available before proceeding
            ExecStartPre = let
              ncCmd = "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${
                  toString db-cfg.port
                }";
              pgWaitCmd =
                "${pkgs.bash}/bin/bash -c 'until ${ncCmd}; do sleep 1; done;'";
            in pkgs.writeShellScript "powerdns-initialize-db-prep.sh" ''
              ${pgWaitCmd}
            '';
            # Initialize PowerDNS schema and all configured domains
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

              # Check if PowerDNS schema already exists
              if [ "$( psql --dbname=${db-cfg.database} -U ${cfg.database.user} -tAc "SELECT to_regclass('public.domains')" )" ]; then
                echo "PowerDNS database schema already initialized, skipping"
              else
                echo "Initializing PowerDNS database schema"
                psql --dbname=${db-cfg.database} -U ${cfg.database.user} -f ${pkgs.powerdns}/share/doc/pdns/schema.pgsql.sql
              fi

              # Initialize all configured domains
              ${domainInitScripts}
            '';
            RuntimeDirectory = "nexus-powerdns-initialize-db";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            DynamicUser = true;
          };
          unitConfig.ConditionPathExists = [ cfg.database.password-file ];
        };

        # ============================================================================
        # Main PowerDNS Service
        # ============================================================================
        # Runs the PowerDNS authoritative nameserver
        nexus-powerdns = {
          description = "Nexus PowerDNS authoritative nameserver";
          after =
            [ "network-online.target" "nexus-powerdns-initialize-db.service" ];
          wantedBy = [ "multi-user.target" ];
          requires =
            [ "network-online.target" "nexus-powerdns-initialize-db.service" ];
          path = with pkgs; [ powerdns postgresql util-linux ];
          serviceConfig = let module-directory = "$RUNTIME_DIRECTORY/modules";
          in {
            # Wait for database to be available
            ExecStartPre = let
              ncCmd = "${pkgs.netcat}/bin/nc -z ${db-cfg.host} ${
                  toString db-cfg.port
                }";
            in pkgs.writeShellScript "powerdns-wait-for-db.sh"
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

              # Secure zones with DNSSEC if enabled
              secureZones = let
                signDomain = domain: ''
                  DNSINFO=$(${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY show-zone ${domain})
                  if [[ "$DNSINFO" =~ "No such zone in the database" ]]; then
                    echo "WARNING: Zone ${domain} does not exist in PowerDNS database"
                    logger "WARNING: Zone ${domain} does not exist in PowerDNS database"
                  elif [[ "$DNSINFO" =~ "Zone is not actively secured" ]]; then
                    echo "Securing zone ${domain} with DNSSEC"
                    logger "Securing zone ${domain} with DNSSEC"
                    ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY secure-zone ${domain}
                  elif [[ "$DNSINFO" =~ "No keys for zone" ]]; then
                    echo "Generating DNSSEC keys for zone ${domain}"
                    logger "Generating DNSSEC keys for zone ${domain}"
                    ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY secure-zone ${domain}
                  fi
                  # Rectify zone to ensure DNSSEC signatures are current
                  ${pkgs.powerdns}/bin/pdnsutil --config-dir=$RUNTIME_DIRECTORY rectify-zone ${domain}
                '';
              in pkgs.writeShellScript "nexus-powerdns-secure-zones.sh" ''
                export HOME=$RUNTIME_DIRECTORY
                ${concatStringsSep "\n"
                (map signDomain (attrNames config.nexus.domains))}
              '';

              # PowerDNS launch command
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

        # ============================================================================
        # Serial Increment Service (Manual/Timer Triggered)
        # ============================================================================
        # Manually increments SOA serial for all zones
        # NOTE: Usually not needed due to the database trigger, but useful for forcing updates
        nexus-powerdns-increment-serial = {
          description = "Manually increment PowerDNS zone serials";
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          path = with pkgs; [ powerdns ];
          serviceConfig = let
            genConfig = genPdnsConfig {
              target-dir = "$RUNTIME_DIRECTORY";
              inherit (cfg)
                port listen-addresses secondary-servers debug enable-dnssec;
              inherit (config.nexus.database) database;
              db-host = config.nexus.database.host;
              db-user = cfg.database.user;
              db-password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
            };
          in {
            ExecStart =
              pkgs.writeShellScript "nexus-powerdns-increment-serial.sh" ''
                ${genConfig}
                ${concatStringsSep "\n" (mapAttrsToList (domain: _: ''
                  echo "Incrementing serial for zone ${domain}"
                  pdnsutil --config-dir=$RUNTIME_DIRECTORY increase-serial ${domain}
                '') config.nexus.domains)}
              '';
            RuntimeDirectory = "nexus-powerdns-increment-serial";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            Type = "oneshot";
          };
        };

        # ============================================================================
        # Secondary DNS Notification Service
        # ============================================================================
        # Sends NOTIFY messages to secondary DNS servers
        # Triggered by the check-updates service when changes are detected
        nexus-powerdns-notify = {
          description = "Notify secondary DNS servers of zone updates";
          path = with pkgs; [ pdns ];
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          serviceConfig = {
            ExecStart = let
              notifyCmds = let
                zones = mapAttrsToList (_: opts: opts.domain-name)
                  config.nexus.domains;
              in concatLists (map (zone:
                map (ip: ''
                  echo "Notifying ${ip} of updates to ${zone}"
                  pdns_notify ${ip} ${zone}
                '') cfg.secondary-servers) zones);
            in pkgs.writeShellScript "notify-secondary-dns.sh"
            (concatStringsSep "\n" notifyCmds);
            Type = "oneshot";
          };
        };

        # ============================================================================
        # Zone Change Detection and Automatic Notification
        # ============================================================================
        # Monitors zone serial numbers and triggers notifications when changes are detected
        # This is the key service for automatic secondary DNS updates
        nexus-powerdns-check-updates = {
          description = "Detect PowerDNS zone changes and notify secondaries";
          after = [ "nexus-powerdns.service" ];
          requires = [ "nexus-powerdns.service" ];
          path = with pkgs; [ gawk gnugrep powerdns ];
          serviceConfig = let
            genConfig = genPdnsConfig {
              target-dir = "$RUNTIME_DIRECTORY";
              inherit (cfg)
                port listen-addresses secondary-servers debug enable-dnssec;
              inherit (config.nexus.database) database;
              db-host = config.nexus.database.host;
              db-user = cfg.database.user;
              db-password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
            };

            # Script to check if a zone's serial has changed and notify secondaries
            zoneCheckScript = secondaryIps: zone:
              let
                notifyCmds = concatStringsSep "\n" (map (secondaryIp: ''
                  echo "Notifying secondary ${secondaryIp} of changes to ${zone}"
                  pdns_notify ${secondaryIp} ${zone}
                '') secondaryIps);
              in pkgs.writeShellScript
              "nexus-powerdns-check-updates-${zone}.sh" ''
                UPDATE=0
                NEW=$CACHE_DIRECTORY/${zone}_new_serial.txt
                OLD=$CACHE_DIRECTORY/${zone}_old_serial.txt

                # Extract current SOA serial from zone
                pdnsutil --config-dir=$RUNTIME_DIRECTORY list-zone ${zone} | grep 'SOA' | awk '{print $7}' > $NEW

                # Check if this is first run or if serial changed
                if [[ ! -f "$OLD" ]]; then
                   echo "First run for ${zone}, saving initial serial"
                   mv $NEW $OLD
                   UPDATE=1
                else
                   NEW_SERIAL=$(cat $NEW)
                   OLD_SERIAL=$(cat $OLD)
                   if [ "$NEW_SERIAL" = "$OLD_SERIAL" ]; then
                      echo "Zone ${zone}: Serial unchanged ($NEW_SERIAL)"
                   else
                      echo "Zone ${zone}: Serial changed from $OLD_SERIAL to $NEW_SERIAL"
                      mv $NEW $OLD
                      UPDATE=1
                   fi
                fi

                # If zone was updated, rectify and notify secondaries
                if [[ $UPDATE -eq 1 ]]; then
                   echo "Rectifying zone ${zone} and notifying secondaries"
                   pdnsutil --config-dir=$RUNTIME_DIRECTORY rectify-zone ${zone}
                   ${notifyCmds}
                fi
              '';
          in {
            ExecStart =
              pkgs.writeShellScript "nexus-powerdns-check-updates.sh" ''
                ${genConfig}
                ${concatStringsSep "\n"
                (map (zone: "${zoneCheckScript cfg.secondary-servers zone}")
                  (mapAttrsToList (_: opts: opts.domain-name)
                    config.nexus.domains))}
              '';

            RuntimeDirectory = "nexus-powerdns-check-updates";
            CacheDirectory = "nexus-powerdns-check-updates";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            Type = "oneshot";
          };
        };

        # ============================================================================
        # ACME Challenge Cleanup Service
        # ============================================================================
        # Periodically removes old ACME DNS-01 challenge records
        nexus-clear-challenges = {
          description = "Clean up old ACME DNS-01 challenge records";
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          path = with pkgs; [ postgresql ];
          serviceConfig = let
            cleanupScript =
              pkgs.writeText "nexus-powerdns-clear-challenges.sql" ''
                BEGIN;
                -- Delete DNS records for challenges older than 1 day
                DELETE FROM records WHERE id IN (
                  SELECT record_id FROM challenges 
                  WHERE created_at < (CURRENT_DATE - INTERVAL '1 day')
                );
                -- Mark challenges as inactive if their record was deleted
                UPDATE challenges SET active=false 
                WHERE NOT EXISTS (
                  SELECT id FROM records WHERE id=challenges.record_id
                );
                COMMIT;
              '';
          in {
            ExecStart =
              pkgs.writeShellScript "nexus-powerdns-clear-challenges.sh" ''
                export PGPASSWORD=$(cat $CREDENTIALS_DIRECTORY/db.passwd)
                echo "Cleaning up ACME challenges older than 1 day"
                psql -h ${db-cfg.host} -U ${cfg.database.user} -d ${db-cfg.database} -f ${cleanupScript}
                unset PGPASSWORD
              '';
            RuntimeDirectory = "nexus-clear-challenges";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            Type = "oneshot";
          };
        };
      };

      # ============================================================================
      # Systemd Timers
      # ============================================================================
      timers = {
        # Run serial increment hourly (usually unnecessary due to trigger)
        nexus-powerdns-increment-serial = {
          description = "Timer for manual SOA serial increments";
          wantedBy = [ "timers.target" ];
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          timerConfig = {
            OnBootSec = "5m";
            OnUnitActivateSec = "1h";
            Unit = "nexus-powerdns-increment-serial.service";
          };
        };

        # Legacy manual notification timer (kept for compatibility)
        # This is now superseded by the automatic check-updates service
        nexus-powerdns-notify = {
          description = "Timer for manual secondary DNS notifications";
          wantedBy = [ "timers.target" ];
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          timerConfig = {
            OnBootSec = "1m";
            OnUnitActivateSec = "30m";
            Unit = "nexus-powerdns-notify.service";
          };
        };

        # Clean up old ACME challenges daily
        nexus-clear-challenges = {
          description = "Timer for ACME challenge cleanup";
          wantedBy = [ "timers.target" ];
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          timerConfig = {
            OnBootSec = "1m";
            OnUnitActivateSec = "1d";
            Unit = "nexus-clear-challenges.service";
          };
        };

        # IMPORTANT: This is the key timer for automatic secondary DNS updates
        # Runs every 10 minutes to detect changes and notify secondaries
        # You can reduce this interval for faster propagation (e.g., "5m")
        nexus-powerdns-check-updates = {
          description =
            "Timer for automatic zone change detection and notification";
          wantedBy = [ "timers.target" ];
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          timerConfig = {
            OnBootSec = "2m"; # Check 2 minutes after boot
            OnUnitActivateSec =
              "5m"; # Then check every 5 minutes (reduced from 10m)
            Unit = "nexus-powerdns-check-updates.service";
          };
        };
      };
    };
  };
}
