packages:

{ config, lib, pkgs, ... }:

with lib;
let
  hostname = config.networking.hostName;
  # Use Babashka script instead of JVM client
  nexus-client-script = pkgs.writeShellScriptBin "nexus-client" ''
    exec ${pkgs.babashka}/bin/bb ${../bb/nexus-client.clj} "$@"
  '';
  cfg = config.nexus.client;
  sshKeyMap = listToAttrs
    (map (path: nameValuePair (baseNameOf path) path) cfg.ssh-key-files);
  hasSshfps = (lib.length cfg.ssh-key-files) > 0;

  # A host authenticates with exactly one key: its legacy HMAC key against
  # /api/v2, or its Ed25519 private key (once migrated) against /api/v3.
  usingPrivateKey = cfg.private-key-file != null;
  keyCredentialName = if usingPrivateKey then "private.key" else "hmac.key";
  keyCredential = "${keyCredentialName}:${
      if usingPrivateKey then cfg.private-key-file else cfg.hmac-key-file
    }";
  keyFlag = if usingPrivateKey then
    "--private-key-file=$CREDENTIALS_DIRECTORY/${keyCredentialName}"
  else
    "--key-file=$CREDENTIALS_DIRECTORY/${keyCredentialName}";

  # Define domain filters at top level for reuse across services/paths/timers
  publicDomains =
    attrValues (filterAttrs (_: opts: opts.type == "public") cfg.domains);
  privateDomains =
    attrValues (filterAttrs (_: opts: opts.type == "private") cfg.domains);
  tailscaleDomains =
    attrValues (filterAttrs (_: opts: opts.type == "tailscale") cfg.domains);

in {
  imports = [ ./options.nix ];

  config = mkIf cfg.enable {
    assertions = [{
      assertion = (cfg.hmac-key-file != null) != (cfg.private-key-file != null);
      message =
        "nexus.client: exactly one of hmac-key-file (legacy /api/v2) or private-key-file (/api/v3) must be set";
    }];

    systemd = {
      services = let
        nexusClientService = type: domains:
          let
            genSshfps = let
              keygenScript = file:
                "ssh-keygen -r PLACEHOLDER -f $CREDENTIALS_DIRECTORY/${file} | sed 's/PLACEHOLDER IN SSHFP //' > $RUNTIME_DIRECTORY/${hostname}-sshfps-${file}.txt";
              keygenScripts =
                concatStringsSep "\n" (map keygenScript (attrNames sshKeyMap));
              collectSshfps = ''
                cat $RUNTIME_DIRECTORY/${hostname}-sshfps-*.txt > $RUNTIME_DIRECTORY/${hostname}-sshfps.txt
              '';
            in ''
              ${keygenScripts}
              ${collectSshfps}
            '';
            domainList =
              concatStringsSep "," (map ({ domain, ... }: domain) domains);
            serverFlags =
              concatStringsSep " " (map (s: "--servers=${s}") cfg.servers);
            globalDomainAliases = domain:
              config.nexus.domains.${domain}.aliases or {};
            aliasList = concatMap ({ domain, aliases, ... }:
              let
                domainAliasMap = globalDomainAliases domain;
                myAliases = filter (alias:
                  let mappedHost = domainAliasMap.${alias} or null;
                  in mappedHost == null || mappedHost == hostname
                ) aliases;
              in map (alias: "${alias}:${domain}") myAliases) domains;
            aliasFlags =
              concatStringsSep " " (map (a: "--aliases=${a}") aliasList);
            sshfpFlags = if hasSshfps then
              "--sshfp-files=$RUNTIME_DIRECTORY/${hostname}-sshfps.txt"
            else
              "";
          in {
            description = "Nexus DDNS Client - one-shot update for ${type} IPs";
            after = [ "network-online.target" ];
            wants = [ "network-online.target" ];
            path = [ nexus-client-script ] ++ (with pkgs; [ openssh babashka iproute2 ]);
            serviceConfig = {
              Type = "oneshot";
              DynamicUser = true;
              RuntimeDirectory = "nexus-${type}-client";
              CacheDirectory = "nexus-${type}-client";
              LoadCredential = [ keyCredential ]
                ++ (mapAttrsToList (file: path: "${file}:${path}") sshKeyMap);
              ExecStart = pkgs.writeShellScript "nexus-${type}-client.sh" ''
                ${optionalString hasSshfps genSshfps}
                nexus-client \
                  --hostname=${cfg.hostname} \
                  --domains=${domainList} \
                  ${serverFlags} \
                  --port=443 \
                  ${keyFlag} \
                  ${optionalString cfg.ipv4 "--ipv4"} \
                  ${optionalString cfg.ipv6 "--ipv6"} \
                  ${optionalString (type == "private") "--private"} \
                  ${optionalString (type == "tailscale") "--tailscale"} \
                  --state-file=$CACHE_DIRECTORY/state.edn \
                  ${aliasFlags} \
                  ${sshfpFlags} \
                  ${optionalString cfg.verbose "--verbose"}
              '';
            };
          };
      in {
        nexus-public-client = mkIf (publicDomains != [ ])
          (nexusClientService "public" publicDomains);
        nexus-private-client = mkIf (privateDomains != [ ])
          (nexusClientService "private" privateDomains);
        nexus-tailscale-client = mkIf (tailscaleDomains != [ ])
          (nexusClientService "tailscale" tailscaleDomains);
      };

      # Path units for event-driven triggering
      paths = {
        nexus-public-client = mkIf (publicDomains != [ ]) {
          description = "Nexus DDNS Client Path Monitor - public IPs";
          wantedBy = [ "multi-user.target" ];
          pathConfig = {
            PathModified = [ "/sys/class/net" ];
            TriggerLimitIntervalSec = "30s";
            TriggerLimitBurst = 1;
          };
        };
        nexus-private-client = mkIf (privateDomains != [ ]) {
          description = "Nexus DDNS Client Path Monitor - private IPs";
          wantedBy = [ "multi-user.target" ];
          pathConfig = {
            PathModified = [ "/sys/class/net" ];
            TriggerLimitIntervalSec = "30s";
            TriggerLimitBurst = 1;
          };
        };
        nexus-tailscale-client = mkIf (tailscaleDomains != [ ]) {
          description = "Nexus DDNS Client Path Monitor - tailscale IPs";
          wantedBy = [ "multi-user.target" ];
          pathConfig = {
            PathModified = [ "/sys/class/net" ];
            TriggerLimitIntervalSec = "30s";
            TriggerLimitBurst = 1;
          };
        };
      };

      # Timer units for periodic backup triggering
      timers = {
        nexus-public-client = mkIf (publicDomains != [ ]) {
          description = "Nexus DDNS Client Timer - public IPs";
          wantedBy = [ "timers.target" ];
          timerConfig = {
            OnBootSec = "5min";
            OnUnitActiveSec = "1h";
            RandomizedDelaySec = "5min";
          };
        };
        nexus-private-client = mkIf (privateDomains != [ ]) {
          description = "Nexus DDNS Client Timer - private IPs";
          wantedBy = [ "timers.target" ];
          timerConfig = {
            OnBootSec = "5min";
            OnUnitActiveSec = "1h";
            RandomizedDelaySec = "5min";
          };
        };
        nexus-tailscale-client = mkIf (tailscaleDomains != [ ]) {
          description = "Nexus DDNS Client Timer - tailscale IPs";
          wantedBy = [ "timers.target" ];
          timerConfig = {
            OnBootSec = "5min";
            OnUnitActiveSec = "1h";
            RandomizedDelaySec = "5min";
          };
        };
      };
    };
  };
}
