{ config, lib, pkgs, ... }@toplevel:

# PowerDNS primary for Nexus.
#
# Every record change Nexus makes goes through the PowerDNS HTTP API, which
# refuses CNAME conflicts, rectifies the zone for DNSSEC, raises the SOA
# serial (SOA-EDIT-API=INCREASE) and sends NOTIFY to the secondaries
# (also-notify) as part of the change. That is why there is no serial
# trigger, no change-detection timer and no notify job here any more: each
# was making up for a step the old direct-SQL writes skipped.
#
# What is left:
#   nexus-powerdns-api-key        generates the API key once, root-only
#   nexus-powerdns-initialize-db  schema, static records from config, zone
#                                 metadata, DNSSEC keys, rectify, serial bump
#   nexus-powerdns                the server, with the API on 127.0.0.1
#   nexus-powerdns-weekly-serial  a weekly serial bump, so secondaries pick
#                                 up PowerDNS's weekly signature rollover

with lib;
let
  cfg = config.nexus.dns-server;

  db-cfg = config.nexus.database;

  zones = mapAttrsToList (_: opts: opts.domain-name) config.nexus.domains;

  # Generate the PowerDNS configuration into target-dir. The password (and
  # the API key, when given) are appended with printf rather than spliced in
  # with sed, so characters like / and & in them survive.
  genPdnsConfig = { target-dir, password-file, api-key-file ? null }:
    let
      secondary-server-str = concatStringsSep "," cfg.secondary-servers;
      base = pkgs.writeText "pdns.conf" (''
        local-address=${concatStringsSep ", " cfg.listen-addresses}
        local-port=${toString cfg.port}
        primary=yes
        launch=gpgsql
        gpgsql-host=${db-cfg.host}
        gpgsql-port=${toString db-cfg.port}
        gpgsql-dbname=${db-cfg.database}
        gpgsql-user=${cfg.database.user}
        gpgsql-dnssec=${if cfg.enable-dnssec then "yes" else "no"}
      '' + optionalString (cfg.secondary-servers != [ ]) ''
        allow-axfr-ips=${secondary-server-str}
        also-notify=${secondary-server-str}
      '' + optionalString (api-key-file != null) ''
        webserver=yes
        webserver-address=127.0.0.1
        webserver-port=${toString cfg.api-port}
        webserver-allow-from=127.0.0.1,::1
        api=yes
      '' + optionalString cfg.debug ''
        log-dns-details=yes
        log-dns-queries=yes
        loglevel=6
        query-logging=yes
      '');
    in ''
      mkdir -p ${target-dir}
      (
        umask 077
        cat ${base} > ${target-dir}/pdns.conf
        printf 'gpgsql-password=%s\n' "$(cat ${password-file})" >> ${target-dir}/pdns.conf
        ${optionalString (api-key-file != null) ''
          printf 'api-key=%s\n' "$(cat ${api-key-file})" >> ${target-dir}/pdns.conf
        ''}
      )
    '';

  pdnsutil = dir: "${pkgs.pdns}/bin/pdnsutil --config-dir=${dir}";

  # SQL string literal.
  sqlStr = s: "'${replaceStrings [ "'" ] [ "''" ] s}'";

  mkRecord = name: type: content: { inherit name type content; };

  # Refuse a CNAME beside any other data at the same name, whoever writes
  # it: the API already refuses these, but the init SQL and anyone with
  # psql do not go through the API. Knot secondaries reject a whole zone
  # transfer over one such name, and keep serving a stale copy until the
  # zone expires, so it is far cheaper to stop it at the write.
  #
  # Only name and type changes are checked (an UPDATE of content alone
  # cannot create a conflict), and rows with a NULL type are skipped: those
  # are the empty non-terminals PowerDNS's rectify writes.
  cnameGuardSql = ''
    CREATE OR REPLACE FUNCTION nexus_refuse_cname_conflict()
    RETURNS TRIGGER AS $fn$
    BEGIN
      IF NEW.type IS NULL THEN
        RETURN NEW;
      END IF;
      IF NEW.type = 'CNAME' THEN
        IF EXISTS (SELECT 1 FROM records
                   WHERE domain_id = NEW.domain_id AND name = NEW.name
                     AND type IS NOT NULL AND type <> 'CNAME'
                     AND id IS DISTINCT FROM NEW.id) THEN
          RAISE EXCEPTION 'CNAME % conflicts with other records at that name', NEW.name
            USING ERRCODE = 'integrity_constraint_violation';
        END IF;
      ELSIF EXISTS (SELECT 1 FROM records
                    WHERE domain_id = NEW.domain_id AND name = NEW.name
                      AND type = 'CNAME'
                      AND id IS DISTINCT FROM NEW.id) THEN
        RAISE EXCEPTION '% record at % conflicts with the CNAME there', NEW.type, NEW.name
          USING ERRCODE = 'integrity_constraint_violation';
      END IF;
      RETURN NEW;
    END;
    $fn$ LANGUAGE plpgsql;

    CREATE OR REPLACE TRIGGER nexus_refuse_cname_conflict
    BEFORE INSERT OR UPDATE OF name, type ON records
    FOR EACH ROW EXECUTE FUNCTION nexus_refuse_cname_conflict();
  '';

  # The challenges table. Each challenge now keeps its own secret: the
  # TXT rrset for a name is rebuilt from every active challenge on it, so
  # record_id is no longer needed (or set).
  challengeTableSql = ''
    CREATE TABLE IF NOT EXISTS challenges (
      domain_id    INTEGER NOT NULL,
      challenge_id UUID NOT NULL,
      hostname     VARCHAR(255) NOT NULL,
      created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
      record_id    BIGINT,
      secret       TEXT,
      active       BOOLEAN NOT NULL DEFAULT TRUE,
      PRIMARY KEY(domain_id, challenge_id)
    );
    ALTER TABLE challenges ADD COLUMN IF NOT EXISTS secret TEXT;
    ALTER TABLE challenges ALTER COLUMN record_id DROP NOT NULL;
  '';

  # The trigger that counted changes in domains.notified_serial. PowerDNS
  # owns that column (it records the serial it last sent NOTIFY for, and
  # resets it about once a minute), so the count was never reliable.
  #
  # Dropping it is cleanup, not something the rest of the run needs, so a
  # role that does not own the trigger or function (on an older database
  # they were created by hand, as another role) only gets a warning. Before
  # this, the "must be owner" error rolled back the whole run and the
  # unit failed on every boot.
  dropSerialTriggerSql = ''
    DO $do$
    BEGIN
      DROP TRIGGER IF EXISTS update_serial_on_change_trigger ON records;
      DROP FUNCTION IF EXISTS update_zone_serial_on_change();
    EXCEPTION WHEN insufficient_privilege THEN
      RAISE WARNING 'could not drop the legacy serial trigger (%); drop it as its owner: DROP TRIGGER IF EXISTS update_serial_on_change_trigger ON records; DROP FUNCTION IF EXISTS update_zone_serial_on_change();', SQLERRM;
    END;
    $do$;
  '';

  # Make the rrset name/type hold exactly contents: rows with other content
  # are removed, missing ones added, and existing ones left alone (so their
  # ids, ordername and auth survive). Config owns any rrset it declares;
  # rrsets it does not declare are never touched. A record that would sit
  # beside a CNAME is skipped with a warning rather than failing the run.
  reconcileRrset = domain: { name, type, contents }:
    let
      domainId = "(SELECT id FROM domains WHERE name=${sqlStr domain})";
      contentList = concatMapStringsSep ", " sqlStr contents;
    in ''
      DELETE FROM records
        WHERE domain_id=${domainId} AND name=${sqlStr name} AND type=${sqlStr type}
          AND content NOT IN (${contentList});
      ${concatMapStringsSep "\n" (content: ''
        BEGIN
          INSERT INTO records (domain_id, name, type, content, auth)
            SELECT ${domainId}, ${sqlStr name}, ${sqlStr type}, ${sqlStr content}, TRUE
            WHERE NOT EXISTS (SELECT 1 FROM records
                              WHERE domain_id=${domainId} AND name=${sqlStr name}
                                AND type=${sqlStr type} AND content=${sqlStr content});
        EXCEPTION WHEN integrity_constraint_violation THEN
          RAISE WARNING 'skipped % % %: %', ${sqlStr name}, ${sqlStr type}, ${sqlStr content}, SQLERRM;
        END;
      '') contents}
    '';

  # Group records into rrsets by (name, type).
  toRrsets = records:
    let
      key = r: "${r.name}\t${r.type}";
      grouped = groupBy key records;
    in mapAttrsToList (_: rs: {
      inherit (head rs) name type;
      contents = unique (map (r: r.content) rs);
    }) grouped;

  # SQL to initialize a domain: the domain row, its SOA, and the static
  # records from config. Runs in one transaction with ON_ERROR_STOP, so a
  # mistake fails the unit visibly instead of half-applying -- the previous
  # version of this script nested `$$` quoting inside `DO $$` and had been
  # failing silently (psql exits 0 without ON_ERROR_STOP) ever since.
  initializeDomainSql = domain:
    let
      inherit (domain) domain-name;
      ipv6-net = net: (builtins.match ".*:.*" net) != null;
      ipv4-net = net: !(ipv6-net net);

      primaryNameserver = head (attrValues domain.nameservers);
      serial = toString config.instance.build-timestamp;
      soaPrefix = "${primaryNameserver.name}.${domain-name} hostmaster.${domain-name}";
      soaSuffix = "${toString domain.refresh} ${toString domain.retry} ${toString domain.expire} ${toString domain.minimum}";
      domainId = "(SELECT id FROM domains WHERE name=${sqlStr domain-name})";

      ns-host-records = concatMap (nsOpts:
        (optional (nsOpts.ipv4-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "A" nsOpts.ipv4-address))
        ++ (optional (nsOpts.ipv6-address != null)
          (mkRecord "${nsOpts.name}.${domain-name}" "AAAA"
            nsOpts.ipv6-address))) (attrValues domain.nameservers);

      ns-records = map (nsOpts:
        mkRecord domain-name "NS" "${nsOpts.name}.${domain-name}")
        (attrValues domain.nameservers);

      static-records = [
        # DMARC policy for email authentication
        (mkRecord "_dmarc.${domain-name}" "TXT" ''
          "v=DMARC1; p=reject; rua=mailto:${domain.admin}; ruf=mailto:${domain.admin}; fo=1;"'')
        # SPF record defining authorized mail senders
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
          domain.aliases) ++ domain.records ++ ns-host-records ++ ns-records;

    in ''
      INSERT INTO domains (name, type)
        SELECT ${sqlStr domain-name}, 'MASTER'
        WHERE NOT EXISTS (SELECT 1 FROM domains WHERE name=${sqlStr domain-name});

      -- The SOA serial only ever goes up. PowerDNS raises it on every API
      -- change, so writing the build's serial unconditionally (as this used
      -- to) would move it backwards after any change since the last build,
      -- and secondaries ignore a serial lower than the one they hold.
      INSERT INTO records (domain_id, name, type, content, auth)
        SELECT ${domainId}, ${sqlStr domain-name}, 'SOA',
               ${sqlStr "${soaPrefix} ${serial} ${soaSuffix}"}, TRUE
        WHERE NOT EXISTS (SELECT 1 FROM records
                          WHERE domain_id=${domainId} AND type='SOA');
      UPDATE records
        SET content = ${sqlStr soaPrefix} || ' '
                      || GREATEST(split_part(content, ' ', 3)::bigint, ${serial})
                      || ' ' || ${sqlStr soaSuffix}
        WHERE domain_id=${domainId} AND type='SOA';

      DO $do$
      BEGIN
      ${concatMapStringsSep "\n" (reconcileRrset domain-name) (toRrsets static-records)}
      END;
      $do$;
    '';

  initSqlFile = pkgs.writeText "nexus-powerdns-init.sql" ''
    ${dropSerialTriggerSql}
    ${cnameGuardSql}
    ${challengeTableSql}
    ${concatMapStringsSep "\n" initializeDomainSql (attrValues config.nexus.domains)}
  '';

  # Per-zone maintenance after the SQL: make API changes raise the serial,
  # sign the zone (once), rectify, bump the serial so secondaries pick up
  # whatever the SQL changed, then check the zone.
  zoneMaintenance = dir: zone: ''
    if ! ${pdnsutil dir} zone list-keys ${zone} >/dev/null 2>&1; then
      echo "ERROR: zone ${zone} is not in the PowerDNS database" >&2
      exit 1
    fi

    ${pdnsutil dir} metadata set ${zone} SOA-EDIT-API INCREASE
    ${pdnsutil dir} metadata set ${zone} SOA-EDIT

    ${optionalString cfg.enable-dnssec ''
      MARKER="$STATE_DIRECTORY/${zone}.signed"
      KEYS="$(${pdnsutil dir} zone list-keys ${zone})"
      # A key row starts with the zone name; the header row does not.
      if grep -qE '^${replaceStrings ["."] ["\\."] zone}[[:space:]].*[[:space:]]Act[[:space:]]' <<< "$KEYS"; then
        touch "$MARKER"
      elif [[ -e "$MARKER" ]] && [[ "${boolToString cfg.allow-new-dnssec-keys}" != "true" ]]; then
        echo "ERROR: zone ${zone} was signed before but has no DNSSEC keys now." >&2
        echo "New keys would not match the DS record at the parent, and every" >&2
        echo "validating resolver would reject the zone. Restore the cryptokeys" >&2
        echo "table, or set nexus.dns-server.allow-new-dnssec-keys to re-key" >&2
        echo "deliberately and then update the DS at the registrar." >&2
        exit 1
      else
        echo "Signing zone ${zone} with new DNSSEC keys"
        ${pdnsutil dir} zone secure ${zone}
        touch "$MARKER"
      fi
    ''}

    ${pdnsutil dir} zone rectify ${zone}
    ${pdnsutil dir} zone increase-serial ${zone}

    # The trigger stops new CNAME conflicts, but not one already in the
    # database, and Knot refuses every transfer of a zone that holds one.
    # PowerDNS serves such a zone without complaint, so this is the one
    # place it gets noticed. Every zone is still checked (and the server
    # still starts); the unit fails at the end, naming them.
    if ! ${pdnsutil dir} zone check ${zone}; then
      FAILED_CHECKS="$FAILED_CHECKS ${zone}"
    fi
  '';

  apiKeyDir = dirOf cfg.api-key-file;

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    networking.firewall = {
      allowedTCPPorts = [ cfg.port ];
      allowedUDPPorts = [ cfg.port ];
    };

    systemd = {
      services = {
        # Generate the API key the first time; it then persists. Root-only:
        # PowerDNS and the Nexus server get it through LoadCredential.
        nexus-powerdns-api-key = {
          description = "Generate the PowerDNS API key for Nexus";
          serviceConfig = {
            Type = "oneshot";
            RemainAfterExit = true;
          };
          script = ''
            if [ ! -s ${cfg.api-key-file} ]; then
              mkdir -p ${apiKeyDir}
              chmod 700 ${apiKeyDir}
              umask 077
              ${pkgs.openssl}/bin/openssl rand -hex 32 > ${cfg.api-key-file}
            fi
          '';
        };

        nexus-powerdns-initialize-db = let
          pgpassFile = "$RUNTIME_DIRECTORY/.pgpass";
          confDir = "$RUNTIME_DIRECTORY/pdns";
        in {
          description =
            "Initialize the PowerDNS database, static records and zone settings";
          before = [ "nexus-powerdns.service" ];
          after = [ "network-online.target" ];
          wants = [ "network-online.target" ];
          path = with pkgs; [ postgresql netcat gnugrep ];
          environment = {
            PGHOST = db-cfg.host;
            PGPORT = toString db-cfg.port;
            PGDATABASE = db-cfg.database;
            PGUSER = cfg.database.user;
          };
          serviceConfig = {
            Type = "oneshot";
            RemainAfterExit = true;
            RuntimeDirectory = "nexus-powerdns-initialize-db";
            StateDirectory = "nexus-powerdns-initialize-db";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
            DynamicUser = true;
          };
          unitConfig.ConditionPathExists = [ cfg.database.password-file ];
          script = ''
            set -euo pipefail

            until nc -z ${db-cfg.host} ${toString db-cfg.port}; do sleep 1; done

            (
              umask 077
              printf '%s:%s:%s:%s:%s\n' ${db-cfg.host} ${toString db-cfg.port} \
                ${db-cfg.database} ${cfg.database.user} \
                "$(cat $CREDENTIALS_DIRECTORY/db.passwd)" > ${pgpassFile}
            )
            export HOME=$RUNTIME_DIRECTORY
            export PGPASSFILE=${pgpassFile}

            if [ -z "$(psql -tAc "SELECT to_regclass('public.domains')")" ]; then
              echo "Initializing PowerDNS database schema"
              psql -v ON_ERROR_STOP=1 -1 -f ${pkgs.pdns}/share/doc/pdns/schema.pgsql.sql
            fi

            psql -v ON_ERROR_STOP=1 -1 -f ${initSqlFile}

            ${genPdnsConfig {
              target-dir = confDir;
              password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
            }}
            FAILED_CHECKS=""
            ${concatMapStringsSep "\n" (zoneMaintenance confDir) zones}
            if [ -n "$FAILED_CHECKS" ]; then
              echo "ERROR: pdnsutil zone check found errors in:$FAILED_CHECKS" >&2
              echo "Secondaries will refuse these zones until they are fixed." >&2
              exit 1
            fi
          '';
        };

        nexus-powerdns = {
          description = "Nexus PowerDNS authoritative nameserver";
          # wants, not requires: a failed initialization is visible in
          # `systemctl --failed`, but it does not take DNS down with it.
          wants = [ "network-online.target" "nexus-powerdns-initialize-db.service" ];
          requires = [ "nexus-powerdns-api-key.service" ];
          after = [
            "network-online.target"
            "nexus-powerdns-initialize-db.service"
            "nexus-powerdns-api-key.service"
          ];
          wantedBy = [ "multi-user.target" ];
          path = with pkgs; [ pdns netcat ];
          serviceConfig = {
            RuntimeDirectory = "nexus-powerdns";
            LoadCredential = [
              "db.passwd:${cfg.database.password-file}"
              "api.key:${cfg.api-key-file}"
            ];
            TimeoutStartSec = "180";
            Restart = "on-failure";
          };
          unitConfig.ConditionPathExists = [ cfg.database.password-file ];
          script = ''
            until nc -z ${db-cfg.host} ${toString db-cfg.port}; do sleep 1; done
            ${genPdnsConfig {
              target-dir = "$RUNTIME_DIRECTORY";
              password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
              api-key-file = "$CREDENTIALS_DIRECTORY/api.key";
            }}
            exec ${pkgs.pdns}/bin/pdns_server --daemon=no --guardian=no \
              --config-dir="$RUNTIME_DIRECTORY" ${
                optionalString cfg.debug "--log-dns-queries=yes --loglevel=7"
              }
          '';
        };

        # PowerDNS signs on the fly and rolls signatures weekly, but
        # secondaries keep the signatures they last transferred, which
        # expire a few weeks later. A zone that goes quiet for that long
        # would be served with expired signatures. A weekly serial bump
        # makes the secondaries transfer fresh ones.
        nexus-powerdns-weekly-serial = let confDir = "$RUNTIME_DIRECTORY";
        in {
          description = "Weekly PowerDNS serial bump for DNSSEC re-signing";
          requires = [ "nexus-powerdns.service" ];
          after = [ "nexus-powerdns.service" ];
          serviceConfig = {
            Type = "oneshot";
            DynamicUser = true;
            RuntimeDirectory = "nexus-powerdns-weekly-serial";
            LoadCredential = "db.passwd:${cfg.database.password-file}";
          };
          script = ''
            set -euo pipefail
            export HOME=$RUNTIME_DIRECTORY
            ${genPdnsConfig {
              target-dir = confDir;
              password-file = "$CREDENTIALS_DIRECTORY/db.passwd";
            }}
            ${concatMapStringsSep "\n"
            (zone: "${pdnsutil confDir} zone increase-serial ${zone}") zones}
          '';
        };
      };

      timers.nexus-powerdns-weekly-serial = {
        description = "Weekly PowerDNS serial bump for DNSSEC re-signing";
        wantedBy = [ "timers.target" ];
        timerConfig = {
          OnCalendar = "weekly";
          Persistent = true;
          RandomizedDelaySec = "1h";
        };
      };
    };
  };
}
