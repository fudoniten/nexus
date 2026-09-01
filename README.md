# Nexus DDNS System

Dynamic DNS system with mutual HMAC authentication for secure IP address and SSH fingerprint updates.

## Components

This monorepo contains all components of the Nexus DDNS system:

- **nexus-crypto**: HMAC key generation and validation library
- **nexus-client**: Client daemon that reports IP addresses and SSH fingerprints
- **nexus-server**: Server API that updates PowerDNS records
- **nexus-keygen**: CLI utility for generating HMAC keys

## Building

### With Nix

```bash
# Build all components
nix build .#nexus-client
nix build .#nexus-server
nix build .#nexus-keygen

# Or build all at once
nix build .#nexus-client .#nexus-server .#nexus-keygen

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

- **nexus.crypto-test**: HMAC key generation and signature validation
- **nexus.keygen-test**: Key generation CLI functionality
- **nexus.client-test**: Client request building and authentication
- **nexus.server-test**: Server routing, authentication, and datastore integration
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
            hmac-key-file = "/path/to/hmac.key";
          };
        }
      ];
    };
  };
}
```

### Configuration Options

See component-specific documentation:
- [Crypto Library](doc/crypto-README.md)
- [Client Configuration](doc/client-README.md)
- [Server Configuration](doc/server-README.md)

## Architecture

```
┌─────────────────┐         HTTPS          ┌─────────────────┐
│  nexus-client   │ ───────────────────────>│  nexus-server   │
│                 │   HMAC authenticated    │                 │
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
│ - Validate HMAC │                         │ - AAAA records  │
└─────────────────┘                         │ - SSHFP records │
                                            └─────────────────┘
```

### How It Works

1. **Key Generation**: Use `nexus-keygen` to generate a shared HMAC key (legacy) or, with `--keypair`, an Ed25519 keypair
2. **Client**: Runs periodically (default: every 60 seconds) to:
   - Discover local IP addresses (public, private, or Tailscale)
   - Read SSH host key fingerprints
   - Send authenticated updates to configured servers
3. **Server**: Receives authenticated requests and:
   - Validates the request signature and timestamp (60s window)
   - Updates PowerDNS records in PostgreSQL
   - Increments SOA serial (via database trigger)
4. **PowerDNS**: Serves updated DNS records to clients

### Security

- **Two authentication schemes, side by side**: the legacy `/api/v2` API authenticates requests with a symmetric HMAC key shared by client and server; the newer `/api/v3` API authenticates requests signed with a per-host Ed25519 private key, verified by the server against that host's public key. A server can run both at once, so hosts can migrate one at a time. See [Migrating to public-key authentication](#migrating-to-public-key-authentication) below.
- **Public keys are not secret**: unlike an HMAC key, a host's Ed25519 public key needs no confidential handling on the server -- it can be committed in plaintext (e.g. to version control) via `nexus.server.host-public-keys`. Only the corresponding private key, held solely by that host, needs to be kept secret.
- **Replay protection**: Timestamps must be within 60 seconds
- **Per-host keys**: Each client has its own key (HMAC or Ed25519)
- **HTTPS**: All communication encrypted in transit

### Migrating to public-key authentication

Each host can move from its shared HMAC key to its own Ed25519 keypair independently, with `/api/v2` and `/api/v3` running side by side on the server throughout:

1. Generate a keypair for the host: `nexus-keygen --keypair host.key` (writes `host.key`, the private key, and `host.key.pub`, the public key).
2. Add the public key to the server's `nexus.server.host-public-keys.<hostname>` option (a plain, non-secret Nix value -- no secret-management step needed).
3. Deliver `host.key` (the private key) to the host as a secret, and set `nexus.client.private-key-file` to its path, in place of `nexus.client.hmac-key-file`.
4. Redeploy the host; it now signs its updates with the Ed25519 key against `/api/v3`.
5. Once every host has migrated, `nexus.server.host-keys`/`client-keys-file` (the legacy HMAC key vault) can be retired.

## Development

### Project Structure

```
nexus/
├── src/nexus/
│   ├── crypto.clj              # Core crypto functions
│   ├── keygen.clj              # Key generation CLI
│   ├── client.clj              # Client protocol
│   ├── client/cli.clj          # Client entry point
│   ├── server.clj              # Server routing
│   ├── server/cli.clj          # Server entry point
│   ├── authenticator.clj       # HMAC validation
│   ├── datastore.clj           # Storage protocol
│   ├── sql_datastore.clj       # PostgreSQL impl
│   ├── host_alias_map.clj      # Hostname aliases
│   ├── logging.clj             # JSON logging
│   └── metrics.clj             # Prometheus metrics
├── test/nexus/
│   ├── crypto_test.clj
│   ├── client_test.clj
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
