packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.nexus.client;
  sshKeyMap = listToAttrs
    (map (path: nameValuePair (baseNameOf path) path) cfg.ssh-key-files);
  sshfpFile = "/run/nexus-client/sshpfs.txt";
  hasSshfps = cfg.ssh-key-files != [ ];

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    systemd = {
      tmpfiles.rules = optional hasSshfps "d ${dirOf sshfpFile} 0700 - - 1d -";

      services = {
        nexus-client-sshpfs = mkIf hasSshfps {
          wantedBy = [ "nexus-client.service" ];
          path = with pkgs; [ openssh ];
          serviceConfig = {
            LoadCredential =
              mapAttrsToList (file: path: "${file}:${path}") sshKeyMap;
            ReadWritePath = [ sshfpFile ];
            ExecStart = let
              keygenScript = file:
                "ssh-keygen -r PLACEHOLDER -f $CREDENTIAL_DIRECTORY/${file} | sed 's/PLACEHOLDER IN SSHFP '/ > ${sshfpFile}";
              keygenScripts =
                concatStringsSep "\n" (map keygenScript (attrNames sshKeyMap));
            in pkgs.writeShellScript "gen-sshfps.sh" ''
              [ -f ${sshfpFile} ] && rm ${sshfpFile}
              touch ${sshfpFile}
              ${keygenScripts}
            '';
          };
        };

        nexus-client = {
          path = [ nexus-client ];
          wantedBy = [ "network-online.target" ];
          serviceConfig = {
            DynamicUser = true;
            RuntimeDirectory = "nexus-client";
            LoadCredential = [ "hmac.key:${cfg.hmac-key-file}" ]
              ++ (optional hasSshfps "sshfp.txt:${sshfpFile}");
            ExecStart = pkgs.writeShellScript "nexus-client.sh"
              (concatStringsSep " " ([
                "nexus-client"
                "--verbose"
                "--port=${toString config.nexus.server.port}"
                "--delay-seconds=${toString cfg.delay-seconds}"
                "--hostname=${cfg.hostname}"
                "--key-file=$CREDENTIALS_DIRECTORY/hmac.key"
              ] ++ (map (srv: "--server=${srv}") cfg.servers)
                ++ (map (dom: "--domain=${dom}") cfg.domains)
                ++ (optional cfg.ipv4 "--ipv4") ++ (optional cfg.ipv6 "--ipv6")
                ++ (optional hasSshfps
                  "--sshfps=$CREDENTIALS_DIRECTORY/sshfp.txt")));
          };
        };
      };
    };
  };
}
