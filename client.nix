packages:

{ config, lib, pkgs, ... }:

with lib;
let
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.nexus.client;
  sshKeyMap = listToAttrs
    (map (path: nameValuePair (baseNameOf path) path) cfg.ssh-key-files);
  sshfpFile = "/run/nexus-client/sshpfs.txt";
  hasSshfps = (lib.length cfg.ssh-key-files) > 0;

  pthru = msg: o: trace "${msg}: ${toString o}" o;

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    systemd = {
      tmpfiles.rules = pthru config.instance.hostname
        (optional hasSshfps "d ${dirOf sshfpFile} 0700 - - - -");

      services = {
        nexus-client-sshpfs = mkIf hasSshfps {
          requiredBy = [ "nexus-client.service" ];
          before = [ "nexus-client.service" ];
          path = with pkgs; [ openssh ];
          serviceConfig = {
            Type = "oneshot";
            LoadCredential =
              mapAttrsToList (file: path: "${file}:${path}") sshKeyMap;
            ExecStart = let
              keygenScript = file:
                "ssh-keygen -r PLACEHOLDER -f $CREDENTIALS_DIRECTORY/${file} | sed 's/PLACEHOLDER IN SSHFP //' > ${sshfpFile}";
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
          wantedBy = [ "multi-user.target" ];
          after = [ "network-online.target" ];
          serviceConfig = {
            DynamicUser = true;
            RuntimeDirectory = "nexus-client";
            LoadCredential = [ "hmac.key:${cfg.hmac-key-file}" ]
              ++ (optional hasSshfps "sshfp.txt:${sshfpFile}");
            ExecStart = pkgs.writeShellScript "nexus-client.sh"
              (concatStringsSep " " ([
                "nexus-client"
                "--port=${toString config.nexus.server.port}"
                "--delay-seconds=${toString cfg.delay-seconds}"
                "--hostname=${cfg.hostname}"
                "--key-file=$CREDENTIALS_DIRECTORY/hmac.key"
              ] ++ (map (srv: "--server=${srv}") cfg.servers)
                ++ (map (dom: "--domain=${dom}") cfg.domains)
                ++ (map (ca: "--certificate-authority=${ca}")
                  cfg.certificate-authorities) ++ (optional cfg.ipv4 "--ipv4")
                ++ (optional cfg.ipv6 "--ipv6") ++ (optional hasSshfps
                  "--sshfps=$CREDENTIALS_DIRECTORY/sshfp.txt")
                ++ (optional cfg.verbose "--verbose")));
          };
        };
      };
    };
  };
}
