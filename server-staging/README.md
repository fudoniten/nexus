# Nexus Dynamic DNS Server

This is the server component for the Nexus dynamic DNS system. It accepts update reports from Nexus clients to dynamically update DNS records, including:

- IPv4 (A records)
- IPv6 (AAAA records) 
- SSH fingerprints (SSHFP records)

## Key Features

- Accepts authenticated update requests from Nexus clients
- Stores DNS records in a SQL database
- Provides an API for retrieving current DNS records
- Supports ACME DNS-01 challenges for automated TLS certificate provisioning

## Server API

The server exposes a REST API for clients to report IP addresses and SSHFP records:

- `PUT /api/v2/domain/:domain/host/:host/ipv4` - Update IPv4 address 
- `PUT /api/v2/domain/:domain/host/:host/ipv6` - Update IPv6 address
- `PUT /api/v2/domain/:domain/host/:host/sshfps` - Update SSHFP records
- `GET /api/v2/domain/:domain/host/:host/ipv4` - Get current IPv4 address
- `GET /api/v2/domain/:domain/host/:host/ipv6` - Get current IPv6 address 
- `GET /api/v2/domain/:domain/host/:host/sshfps` - Get current SSHFP records

It also has an API for ACME DNS-01 challenge management:

- `GET /api/v2/domain/:domain/challenges/list` - List active challenges 
- `PUT /api/v2/domain/:domain/challenge/:challenge-id` - Create challenge record
- `DELETE /api/v2/domain/:domain/challenge/:challenge-id` - Delete challenge record

## Configuration

See `config.edn` for the available configuration options. The server requires:

- SQL database connection info
- Authenticator configuration (API keys, etc)

## Command-line Options

The Nexus server CLI supports the following command-line options:

`-k HOST_KEYS, --host-keys HOST_KEYS`
: File containing host/key pairs, in json format.

`-c CHALLENGE_KEYS, --challenge-keys CHALLENGE_KEYS` 
: File containing challenge keys, in json format.

`-M HOST_ALIAS_MAP, --host-alias-map HOST_ALIAS_MAP`
: File containing host to domain/alias mapping, in json format.

`-D DATABASE, --database DATABASE`
: Database name of the PowerDNS database.

`-U DB_USER, --database-user DB_USER`
: User as which to connect to the PowerDNS database.

`-W DB_PASSWORD_FILE, --database-password-file DB_PASSWORD_FILE`
: File containing password with which to connect to the PowerDNS database.

`-H DB_HOSTNAME, --database-host DB_HOSTNAME`
: Hostname of the Postgresql PowerDNS database.

`-P DB_PORT, --database-port DB_PORT`
: Port of the Postgresql server backing PowerDNS. Defaults to 5432.

`-h HOST, --listen-host HOST`
: Hostname or IP address on which to listen. 

`-p PORT, --listen-port PORT`
: Port on which to listen for incoming requests.

`-v, --verbose`
: Enable verbose output.

`-V, --version`
: Print the current version.

## Development

Run locally with:

```
clojure -M:run
```

Run tests with:

```
clojure -M:test
```
