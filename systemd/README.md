# Nexus Client Event-Driven Architecture

This directory contains systemd unit templates for running the Nexus DDNS client in an event-driven, resource-efficient manner.

## Architecture

Instead of a long-running daemon that polls every 60 seconds, the new architecture uses:

1. **One-shot service** (`nexus-client@.service`) - Runs the Babashka client script once per invocation
2. **Path monitoring** (`nexus-client@.path`) - Triggers updates when network interfaces change
3. **Timer backup** (`nexus-client@.timer`) - Ensures updates run periodically (every hour) even if events are missed

## Benefits

- **Minimal resource usage**: Client only runs when needed (typically a few seconds)
- **Fast response**: Updates triggered immediately when network changes
- **Reliable backup**: Timer ensures updates happen even if path monitoring fails
- **IP change detection**: Client caches last state and only sends updates when IPs change
- **Batch updates**: Single HTTPS request per server instead of multiple

## Usage

### NixOS Integration

The NixOS module (in `nix/client.nix`) will be updated to use these templates instead of the persistent daemon.

### Manual Installation

For non-NixOS systems:

```bash
# Copy systemd units to system directory
sudo cp systemd/nexus-client@.* /etc/systemd/system/

# Create configuration drop-in for your host type (public/private/tailscale)
sudo mkdir -p /etc/systemd/system/nexus-client@public.service.d/
sudo tee /etc/systemd/system/nexus-client@public.service.d/override.conf <<EOF
[Service]
LoadCredential=hmac.key:/path/to/your/hmac.key
Environment="NEXUS_HOSTNAME=$(hostname -s)"
Environment="NEXUS_DOMAINS=example.com"
Environment="NEXUS_SERVERS=dns1.example.com,dns2.example.com"
Environment="NEXUS_PORT=443"
Environment="NEXUS_IPV4_FLAG=--ipv4"
Environment="NEXUS_IPV6_FLAG=--ipv6"
Environment="NEXUS_TYPE_FLAG="
ExecStart=
ExecStart=/usr/bin/env bb /usr/local/bin/nexus-client.clj \\
  --hostname=\${NEXUS_HOSTNAME} \\
  --domains=\${NEXUS_DOMAINS} \\
  --servers=\${NEXUS_SERVERS} \\
  --port=\${NEXUS_PORT} \\
  --key-file=\${CREDENTIALS_DIRECTORY}/hmac.key \\
  \${NEXUS_IPV4_FLAG} \\
  \${NEXUS_IPV6_FLAG}
EOF

# Enable and start the path monitor and timer
sudo systemctl enable --now nexus-client@public.path
sudo systemctl enable --now nexus-client@public.timer

# Manually trigger an update
sudo systemctl start nexus-client@public.service
```

## Monitoring

Check if path monitoring is active:
```bash
systemctl status nexus-client@public.path
```

Check timer schedule:
```bash
systemctl list-timers nexus-client@*
```

View recent updates:
```bash
journalctl -u nexus-client@public.service -n 50
```

## Trigger Behavior

### Path Monitoring Triggers
- Network interface state changes (up/down)
- New network interfaces added
- DHCP lease changes
- IP address changes on existing interfaces

### Timer Triggers
- 5 minutes after boot (waits for network to be fully up)
- Every hour thereafter (with 5-minute randomization to avoid thundering herd)

### Rate Limiting
Path monitoring includes rate limiting to prevent excessive triggers during network instability:
- Maximum 1 trigger per 30 seconds
- This prevents rapid-fire updates when network is flapping

## Customization

### Changing Timer Interval

Edit the timer unit or create a drop-in:
```bash
sudo systemctl edit nexus-client@public.timer
```

Add:
```ini
[Timer]
OnUnitActiveSec=2h  # Change from 1h to 2h
```

### Adding More Triggers

You can add additional path monitoring targets by editing the `.path` unit:
```bash
sudo systemctl edit nexus-client@public.path
```

For example, to also monitor a specific interface:
```ini
[Path]
PathModified=/sys/class/net/eth0/operstate
```

### Verbose Logging

Enable verbose mode by adding to the service override:
```ini
[Service]
Environment="NEXUS_VERBOSE_FLAG=--verbose"
```
