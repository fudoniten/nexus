{ config, lib, pkgs, ... }@toplevel:

with lib;
let
  domainOpts = { name, ... }: {
    options = with lib.types; {
      domain-name = mkOption {
        type = str;
        default = name;
      };

      admin = mkOption {
        type = str;
        description = "Email address of the domain administrator.";
        default = "admin@${name}";
      };

      aliases = mkOption {
        type = attrsOf str;
        description = ''
          Map of alias to CNAME target. Static: written at startup. A name
          here must not also be a host alias (nexus.server.client-alias-map),
          which publishes A/AAAA instead.
        '';
        default = { };
      };

      records = let
        recordOpts = { name, ... }: {
          options = {
            name = mkOption {
              type = str;
              description = "Name of this record.";
              default = name;
            };

            type = mkOption {
              type = str;
              description = "Record type of this record.";
            };

            content = mkOption {
              type = str;
              description = "Data associated with this record.";
            };
          };
        };
      in mkOption {
        type = listOf (submodule recordOpts);
        description = "Records to be manually inserted into the database.";
        default = [ ];
      };

      gssapi-realm = mkOption {
        type = nullOr str;
        description = "GSSAPI (Kerberos) realm associated with this domain.";
        default = null;
      };

      trusted-networks = mkOption {
        type = listOf str;
        description = "List of networks that are trusted for email relay.";
        default = [ ];
      };

      allowed-networks = mkOption {
        type = listOf str;
        description = ''
          CIDR blocks a host may publish its addresses from in this domain.
          The server refuses (403, or 400 for a batch) an update carrying an
          address outside all of them. Empty means any address is accepted.

          For a Tailscale-only domain:
          [ "100.64.0.0/10" "fd7a:115c:a1e0::/48" ].
        '';
        default = [ ];
        example = [ "100.64.0.0/10" "fd7a:115c:a1e0::/48" ];
      };

      nameservers = let
        nameserverOpts = { name, ... }: {
          options = {
            name = mkOption {
              type = str;
              description = "Hostname used for the nameserver.";
              default = name;
            };
            ipv4-address = mkOption {
              type = nullOr str;
              description = "Nameserver V4 IP.";
              default = null;
            };
            ipv6-address = mkOption {
              type = nullOr str;
              description = "Nameserver V6 IP.";
              default = null;
            };
          };
        };
      in mkOption {
        type = attrsOf (submodule nameserverOpts);
        description = "List of nameservers for this domain.";
        default = { };
      };

      refresh = mkOption {
        type = int;
        default = 10800;
      };

      retry = mkOption {
        type = int;
        default = 3600;
      };

      expire = mkOption {
        type = int;
        default = 1209600;
      };

      minimum = mkOption {
        type = int;
        default = 3600;
      };

      timestamp = mkOption {
        type = int;
        description = "Timestamp to attach to the SOA record.";
      };
    };
  };
in {
  options.nexus = with lib.types; {
    domains = mkOption {
      type = attrsOf (submodule domainOpts);
      description = "Map of domains served by Nexus to domain options.";
      default = { };
    };

    database = {
      database = mkOption {
        type = str;
        description = "Name of the database serving this domain.";
        default = "nexus_ddns";
      };

      host = mkOption {
        type = str;
        description = "Hostname of the database server.";
      };

      port = mkOption {
        type = port;
        description = "Port on which to connect to database server.";
        default = 5432;
      };
    };

    dns-server = {
      enable = mkEnableOption "Enable Fudo Nexus DNS server.";

      port = mkOption {
        type = port;
        description = "Port on which to listen for DNS request.";
        default = 53;
      };

      secondary-servers = mkOption {
        type = listOf str;
        description = "List of IPs acting as secondary servers.";
        default = [ ];
      };

      listen-addresses = mkOption {
        type = listOf str;
        description = "List of addresses on which to listen for requests.";
        default = [ "0.0.0.0" ];
      };

      enable-dnssec = mkEnableOption "Enable DNSSEC for this domain.";

      api-port = mkOption {
        type = port;
        description = ''
          Port on 127.0.0.1 for the PowerDNS HTTP API. The Nexus server
          makes every record change through it, so it must run on the same
          host as this DNS server.
        '';
        default = 8081;
      };

      api-key-file = mkOption {
        type = str;
        description = ''
          Where the PowerDNS API key is kept. It is generated on first start
          if missing; only root can read it, and both PowerDNS and the Nexus
          server receive it as a systemd credential.
        '';
        default = "/var/lib/nexus-powerdns-api/api.key";
      };

      allow-new-dnssec-keys = mkOption {
        type = bool;
        description = ''
          Generate DNSSEC keys for a zone that has none. Safe the first
          time a zone is signed; dangerous afterwards, because a zone that
          had keys and lost them (a recreated database) would come back
          signed with keys that do not match the DS record at its parent,
          and every validating resolver would reject it. After a zone has
          been signed once, startup refuses to sign it again with new keys
          unless this is set; set it only to deliberately re-key, and
          update the DS record at the registrar afterwards.
        '';
        default = false;
      };

      debug = mkOption {
        type = bool;
        description = "Enable verbose debugging.";
        default = false;
      };

      database = {
        user = mkOption {
          type = str;
          description = "User as which to connect to the database.";
          default = "nexus_pdns";
        };

        password-file = mkOption {
          type = str;
          description = "File containing database password for <user>.";
          default = "nexus_pdns";
        };
      };
    };

    server = {
      enable = mkEnableOption "Enable Fudo Nexus server.";

      verbose = mkOption {
        type = bool;
        description = "Enable verbose logging.";
        default = false;
      };

      internal-port = mkOption {
        type = port;
        description = "Port on which to listen for requests from nginx.";
        default = 9812;
      };

      hostnames = mkOption {
        type = listOf str;
        description = "Hostnames on which to listen for incoming requests.";
      };

      host-public-keys = mkOption {
        type = attrsOf str;
        description = ''
          Map of hostname to host Ed25519 public key, as written to
          <name>.pub by nexus-generate-key --keypair. Hosts authenticate
          to /api/v3 with the matching private key.

          These are public keys: not secret, and fine to commit.
        '';
        default = { };
      };

      challenge-public-keys = mkOption {
        type = attrsOf str;
        description = ''
          Map of challenge-client name (the "service" a client identifies
          as) to its Ed25519 public key, as written to <name>.pub by
          nexus-generate-key --keypair.

          Not secret; see host-public-keys.
        '';
        default = { };
      };

      client-alias-map = let
        domainAliases = submodule ({ name, ... }: {
          options = {
            domain = mkOption {
              type = str;
              description = "Domain to which these aliases belong";
            };

            aliases = mkOption {
              type = listOf str;
              description = "List of aliases mapping to the given host.";
            };
          };
        });
      in mkOption {
        type = attrsOf (listOf domainAliases);
        description = ''
          Map of host to the aliases it publishes its own A/AAAA records
          under, and may update. An alias must not also be a CNAME
          (nexus.domains.<domain>.aliases); the server assertion checks.
        '';
        default = { };
      };

      database = {
        user = mkOption {
          type = str;
          description = "User as which to connect to the database.";
          default = "nexus_server";
        };

        password-file = mkOption {
          type = str;
          description = "File containing database password for <user>.";
          default = "nexus_server";
        };
      };
    };

    client = {
      enable = mkEnableOption "Enable Nexus DDNS client.";

      verbose = mkOption {
        type = bool;
        description = "Enable verbose logging.";
        default = false;
      };

      hostname = mkOption {
        type = str;
        description =
          "Base hostname of this host. Must match key held by server.";
      };

      servers = mkOption {
        type = listOf str;
        description = "List of servers to notify of changes.";
      };

      certificate-authorities = mkOption {
        type = listOf path;
        description = "List of CA certificates trusted by the client.";
        default = [ ];
      };

      ipv4 = mkOption {
        type = bool;
        description = "Report IPv4 address, if present.";
        default = true;
      };

      ipv6 = mkOption {
        type = bool;
        description = "Report IPv6 address, if present.";
        default = true;
      };

      ssh-key-files = mkOption {
        type = listOf str;
        description = "List of SSH key files to fingerprint for this host.";
        default = [ ];
      };

      domains = mkOption {
        type = attrsOf (submodule ({ name, ... }: {
          options = {
            domain = mkOption {
              type = str;
              default = name;
            };

            type = mkOption {
              type = enum [ "public" "private" "tailscale" ];
              default = "public";
            };

            aliases = mkOption {
              type = listOf str;
              description =
                "List of other 'hostnames' to report to the server.";
              default = [ ];
            };
          };
        }));
        description = "Domain(s) to which this client belongs.";
        default = { };
      };

      private-key-file = mkOption {
        type = str;
        description = ''
          Path (on local host) of file containing this host's Ed25519
          private key, as written by nexus-generate-key --keypair.
          Authenticates against the /api/v3 API.
        '';
      };
    };
  };
}
