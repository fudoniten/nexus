# Nexus Client

This project is a part of the Nexus dynamic DNS system. The Nexus client is responsible for reporting local changes to a Nexus server, such as updating IP addresses and SSHFP records for specific hostnames and domains.

## Features

- Send and receive requests to update or retrieve IP addresses and SSHFP records.
- Supports both IPv4 and IPv6.
- Provides a CLI for easy interaction.
- Authenticates requests with either a shared HMAC key (`--key-file`, legacy `/api/v2`) or a per-host Ed25519 private key (`--private-key-file`, `/api/v3`, generated with `nexus-generate-key --keypair`). Exactly one of the two must be given.

## Usage

The Nexus client can be run from the command line. Use the `--help` option to see available commands and options.

```bash
clojure -M -m nexus.client.cli --help
```

## Testing

To run the tests, use the following command:

```bash
clojure -M:test
```

## License

This project is licensed under the MIT License.
