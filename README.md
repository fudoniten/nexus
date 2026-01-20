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
            hostname = config.instance.hostname;
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

1. **Key Generation**: Use `nexus-keygen` to generate shared HMAC keys
2. **Client**: Runs periodically (default: every 60 seconds) to:
   - Discover local IP addresses (public, private, or Tailscale)
   - Read SSH host key fingerprints
   - Send authenticated updates to configured servers
3. **Server**: Receives authenticated requests and:
   - Validates HMAC signatures and timestamps (60s window)
   - Updates PowerDNS records in PostgreSQL
   - Increments SOA serial (via database trigger)
4. **PowerDNS**: Serves updated DNS records to clients

### Security

- **Mutual authentication**: Both client and server verify HMAC signatures
- **Replay protection**: Timestamps must be within 60 seconds
- **Per-host keys**: Each client has unique HMAC key
- **HTTPS**: All communication encrypted in transit

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
