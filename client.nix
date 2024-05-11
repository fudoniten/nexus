packages:

{ config, lib, pkgs, ... }:

with lib;
let
  hostname = config.instance.hostname;
  nexus-client = packages."${pkgs.system}".nexus-client;
  cfg = config.nexus.client;
  sshKeyMap = listToAttrs
    (map (path: nameValuePair (baseNameOf path) path) cfg.ssh-key-files);
  hasSshfps = (lib.length cfg.ssh-key-files) > 0;

  pthru = o: trace o o;

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    systemd = {
      services = let
        nexusClient = type: domains:
          let
            genSshfps = let
              keygenScript = file:
                "ssh-keygen -r PLACEHOLDER -f $CREDENTIALS_DIRECTORY/${file} | sed 's/PLACEHOLDER IN SSHFP //' > $CACHE_DIRECTORY/sshfps.txt";
              keygenScripts =
                concatStringsSep "\n" (map keygenScript (attrNames sshKeyMap));
            in ''
              ${keygenScripts}
              mv -v $CACHE_DIRECTORY/sshfps.txt $RUNTIME_DIRECTORY/${hostname}-sshfps.txt
            '';
          in {
            wantedBy = [ "multi-user.target" ];
            after = [ "network-online.target" ];
            path = [ nexus-client ] ++ (with pkgs; [ openssh ]);
            serviceConfig = {
              DynamicUser = true;
              Restart = "always";
              RuntimeDirectory = "nexus-client";
              CacheDirectory = optionalString hasSshfps "nexus-client";
              LoadCredential = [ "hmac.key:${cfg.hmac-key-file}" ]
                ++ (mapAttrsToList (file: path: "${file}:${path}") sshKeyMap);
              ExecStart = let
                execScript = concatStringsSep " " ([
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
                    "--sshfps=$RUNTIME_DIRECTORY/${hostname}-sshfps.txt")
                  ++ (optional cfg.verbose "--verbose"));
              in pkgs.writeShellScript "nexus-${type}-client.sh" ''
                ${optionalString hasSshfps genSshfps}
                ${execScript}
              '';
            };
          };

        publicDomains =
          attrNames (filterAttrs (_: opts: opts.type == "public") cfg.domains);

        privateDomains =
          attrNames (filterAttrs (_: opts: opts.type == "private") cfg.domains);

        tailscaleDomains = attrNames
          (filterAttrs (_: opts: opts.type == "tailscale") cfg.domains);
      in {
        nexus-public-client = mkIf (publicDomains != [ ])
          (nexusClient "public" (pthru publicDomains));
        nexus-private-client = mkIf (privateDomains != [ ])
          (nexusClient "private" (pthru privateDomains));
        nexus-tailscale-client = mkIf (publicDomains != [ ])
          (nexusClient "tailscale" (pthru tailscaleDomains));
      };
    };
  };
}
