# Nexus DDNS System

Dynamic DNS system for hosts with changing addresses, and for ACME DNS-01 challenges. Clients sign requests with Ed25519 keys; the server makes every change through the PowerDNS HTTP API.

## Components

This monorepo contains all components of the Nexus DDNS system:

- **nexus-client** (`bb/nexus-client.clj`): Babashka script that reports IP addresses and SSH fingerprints; the `nexus-client` NixOS module runs it
- **nexus-server**: Server API that updates PowerDNS records
- **nexus-keygen**: CLI utility for generating Ed25519 keypairs (`--keypair`)
- **nexus-crypto**: key and signature library

## Building

### With Nix

```bash
nix build .#nexus-server .#nexus-keygen

# Development shell with clojure and tools
nix develop
```

### With Clojure CLI

```bash
# Run all tests
clojure -M:test

# Run specific test namespace
clojure -M:test -n nexus.crypto-test

# Lint code
clojure -M:lint

# Start REPL
clojure
```

## Testing

The project includes comprehensive test coverage across all core namespaces:

- **nexus.crypto-test**: Key generation and signature validation
- **nexus.keygen-test**: Key generation CLI functionality
- **nexus.server-test**: Server routing, authentication, and datastore integration
- **nexus.pdns-datastore-test**: PowerDNS API requests and error mapping
- **nexus.authenticator-test**: Signature validation and key management
- **nexus.host-alias-map-test**: Hostname alias resolution

All tests run automatically on pull requests via GitHub Actions CI.

## NixOS Deployment

Add to your flake inputs:

```nix
{
  inputs.nexus.url = "github:fudoniten/nexus";
  
  outputs = { self, nixpkgs, nexus, ... }: {
    nixosConfigurations.myhost = nixpkgs.lib.nixosSystem {
      modules = [
        nexus.nixosModules.nexus-client
        nexus.nixosModules.nexus-server
        nexus.nixosModules.nexus-powerdns
        
        # Your configuration
        {
          nexus.client = {
            enable = true;
            servers = [ "ddns.example.com" ];
            domains = [ "example.com" ];
            hostname = config.networking.hostName;
            private-key-file = "/path/to/host.key";
          };
        }
      ];
    };
  };
}
```

## Architecture

```
┌─────────────────┐         HTTPS          ┌─────────────────┐
│  nexus-client   │ ───────────────────────>│  nexus-server   │
│                 │ Ed25519-signed (v3)     │                 │
│ - Discover IPs  │   IP/SSHFP updates      │ - Validate auth │
│ - Read SSH FPs  │                         │ - Update DNS    │
└─────────┬───────┘                         └────────┬────────┘
          │                                          │
          │ uses                                     │ writes to
          v                                          v
┌─────────────────┐                         ┌─────────────────┐
│  nexus.crypto   │                         │   PostgreSQL    │
│                 │                         │   (PowerDNS)    │
│ - Generate keys │                         │                 │
│ - Sign requests │                         │ - A records     │
│ - Sign (Ed25519)│                         │ - AAAA records  │
└─────────────────┘                         │ - SSHFP records │
                                            └─────────────────┘
```

### How It Works

1. **Key Generation**: Use `nexus-keygen --keypair` to generate an Ed25519 keypair for each host and challenge client
2. **Client**: Runs periodically (default: every 60 seconds) to:
   - Discover local IP addresses (public, private, or Tailscale)
   - Read SSH host key fingerprints
   - Send authenticated updates to configured servers
3. **Server**: Receives authenticated requests and:
   - Validates the request signature and timestamp (60s window)
   - Applies the change through the PowerDNS HTTP API (on 127.0.0.1). A batch
     update is the host's whole state: a record type it omits is removed.
     A CNAME conflict comes back to the client as 409.
4. **PowerDNS**: In the same change, refuses CNAME conflicts, rectifies the
   zone for DNSSEC, raises the SOA serial (SOA-EDIT-API=INCREASE), and sends
   NOTIFY to the secondaries. A Postgres trigger refuses CNAME conflicts from
   any other writer, and a weekly serial bump makes secondaries pick up
   PowerDNS's weekly signature rollover.

### Security

- **Ed25519 request signatures** on `/api/v3`, for host records and ACME challenge records alike. Each host and each challenge client has its own keypair; the server holds only public keys, which are not secret and can be committed in plaintext via `nexus.server.host-public-keys` and `nexus.server.challenge-public-keys`. The HMAC-signed `/api/v2` has been removed.
- **Replay protection**: Timestamps must be within 60 seconds
- **HTTPS**: All communication encrypted in transit

### Adding a host or challenge client

1. Generate a keypair: `nexus-keygen --keypair host.key` (writes `host.key`, the private key, and `host.key.pub`).
2. Add the public key to the server's `nexus.server.host-public-keys.<hostname>` (or, for a challenge client, `nexus.server.challenge-public-keys.<service>`, keyed by the name it sends in its `Service` header).
3. Deliver the private key to the client as a secret. For a host, set `nexus.client.private-key-file` to its path.

### Aliases

A host alias (`nexus.server.client-alias-map`, and the matching `nexus.client.domains.<domain>.aliases`) is a name the host publishes its own A/AAAA records under. A domain alias (`nexus.domains.<domain>.aliases`) is a static CNAME. A name can be one or the other, never both: the server module refuses to build if a name is in both, and PowerDNS refuses the write at runtime.

## Development

### Project Structure

```
nexus/
├── src/nexus/
│   ├── crypto.clj              # Core crypto functions
│   ├── keygen.clj              # Key generation CLI
│   ├── server.clj              # Server routing
│   ├── server/cli.clj          # Server entry point
│   ├── authenticator.clj       # Ed25519 signature validation
│   ├── datastore.clj           # Storage protocol
│   ├── pdns_datastore.clj      # Writes, through the PowerDNS API
│   ├── sql_datastore.clj       # Reads, straight from PostgreSQL
│   ├── host_alias_map.clj      # Hostname aliases
│   ├── logging.clj             # JSON logging
│   └── metrics.clj             # Prometheus metrics
├── bb/nexus-client.clj         # The client (Babashka)
├── test/nexus/
│   ├── crypto_test.clj
│   ├── pdns_datastore_test.clj
│   └── server_test.clj
├── nix/
│   ├── client.nix              # Client NixOS module
│   ├── server.nix              # Server NixOS module
│   └── powerdns.nix            # PowerDNS setup
├── deps.edn                    # Clojure dependencies
└── flake.nix                   # Nix build configuration
```

### Running Tests

```bash
# All tests
clojure -M:test

# Specific namespace
clojure -M:test -n nexus.crypto-test

# With Nix
nix flake check
```

### Code Quality

```bash
# Lint
clojure -M:lint

# Format (TODO: add formatter)
# clojure -M:format
```

## Repository History

This repository was created in January 2026 by merging four previously separate repositories:
- `nexus-crypto` (crypto library)
- `nexus-client` (client daemon)
- `nexus-server` (server API)
- `nexus` (NixOS deployment wrapper)

All git history has been preserved from the original repositories. The merger resolved dependency version conflicts and unified the build system.

## License

(TODO: Add license information)

## Contributing

(TODO: Add contribution guidelines)
