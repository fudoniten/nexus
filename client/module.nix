packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.fudo.nexus.client;
in {
  imports = [ ../options.nix ];

  config = {
    systemd = {
      service = {
        nexus-client = let
          keys = listToAttrs
            (map (key: nameValuePairs (baseNameOf key.path) key.path)
              config.services.openssh.hostKeys);
        in {
          path = [ nexus-client pkgs.openssh ];
          serviceConfig = {
            DynamicUser = true;
            RuntimeDirectory = "nexus-client";
            LoadCredentials =
              mapAttrsToList (filename: path: "${filename}:${path}") keys;
            ExecStartPre = let
              cmds = map (filename:
                "ssh-keygen -r -f $CREDENTIALS_DIRECTORY/${filename} > $CACHE_DIRECTORY/${filename}.fp")
                (attrNames keys);
            in pkgs.writeShellScript "generate-sshfps.sh" "\n";
            ExecStart = concatStringsSep " " [
              "nexus-client"
              "--server=${config.fudo.nexus.server.hostname}"
              "--port=${config.fudo.nexus.server.port}"
              "--delay-seconds=${toString 5 * 60}"
              "--hostname=${cfg.hostname}"
            ] ++ (optional cfg.ipv4 "--ipv4") ++ (optional cfg.ipv6 "--ipv6")
              ++ (optionals cfg.sshfps
                (map (filename: "--sshfp=${filename}.sp") (attrNames keys)));
          };
        };
      };
    };
  };
}
