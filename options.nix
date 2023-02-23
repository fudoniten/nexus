{ config, lib, pkgs, ... }:

with lib;
let
  domainOpts = { name, ... }: {
    options = with lib.types; {
      domain-name = mkOption {
        type = str;
        default = name;
      };

      aliases = mkOption {
        type = attrsOf str;
        description = "Map of alias to authoritative hostname.";
        default = { };
      };

      gssapi-realm = mkOption {
        type = nullOr str;
        description = "GSSAPI (Kerberos) realm associated with this domain.";
        default = null;
      };

      enable-dnssec = mkEnableOption "Enable DNSSEC for this domain.";

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
              description = "Nameserver V4 IP.";
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
  options.fudo.nexus = with lib.types; {
    admin = mkOption {
      type = str;
      description = "Administrator's email address.";
      default = "admin@${toplevel.config.fudo.nexus.domain}";
    };

    domains = mkOption {
      type = attrsOf domainOpts;
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

      listen-addresses = mkOption {
        type = listOf str;
        descritpion = "List of addresses on which to listen for requests.";
      };

      debug = mkOption {
        type = bool;
        descrition = "Enable verbose debugging.";
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

      host-keys = mkOption {
        type = attrsOf str;
        description = "Map of hostname to host HMAC key.";
        default = { };
      };

      internal-port = mkOption {
        type = port;
        description = "Port on which to listen for requests from nginx.";
        default = 9812;
      };

      hostname = mkOption {
        type = str;
        description = "Hostname on which to listen for incoming requests.";
        default = "nexus.${toplevel.config.fudo.nexus.server.domain}";
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

      hostname = mkOption {
        type = str;
        description =
          "Base hostname of this host. Must match key held by server.";
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

      sshfps = mkOption {
        type = bool;
        description = "Report SSH host key fingerprints.";
        default = true;
      };
    };
  };
}
