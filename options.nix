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
        description = "Map of alias to authoritative hostname.";
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

    secondary-servers = mkOption {
      type = listOf str;
      description = "List of IPs acting as secondary servers.";
      default = [ ];
    };

    dns-server = {
      enable = mkEnableOption "Enable Fudo Nexus DNS server.";

      port = mkOption {
        type = port;
        description = "Port on which to listen for DNS request.";
        default = 53;
      };

      listen-addresses = mkOption {
        type = listOf str;
        description = "List of addresses on which to listen for requests.";
        default = [ "0.0.0.0" ];
      };

      enable-dnssec = mkEnableOption "Enable DNSSEC for this domain.";

      debug = mkOption {
        type = bool;
        description = "Enable verbose debugging.";
        default = false;
      };

      database = {
        user = mkOption {
          type = str;
          description = "User as which to connect to the database.";
          default = "nexus_powerdns";
        };

        password-file = mkOption {
          type = str;
          description = "File containing database password for <user>.";
          default = "nexus_powerdns";
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

      host-keys = mkOption {
        type = attrsOf str;
        description = "Map of hostname to host HMAC key.";
        default = { };
      };

      port = mkOption {
        type = port;
        description = "External port on which to listen for requests.";
        default = 443;
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

      client-keys-file = mkOption {
        type = str;
        description =
          "Path (on the local host) to JSON file containing a hostname to HMAC key.";
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
        type = listOf str;
        description = "Domain(s) to which this client belongs.";
        default = [ ];
      };

      delay-seconds = mkOption {
        type = int;
        description = "Seconds to wait before reporting IP addresses.";
        default = 60;
      };

      hmac-key-file = mkOption {
        type = str;
        description = "Path (on local host) of file containing HMAC key.";
      };
    };
  };
}
