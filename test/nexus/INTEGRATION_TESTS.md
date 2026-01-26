# Integration Tests

This directory contains integration tests for the Nexus DDNS system that verify end-to-end functionality including:

- Real PostgreSQL database operations
- HTTP request/response handling
- HMAC authentication
- Database schema initialization
- DNS record persistence

## Running Integration Tests

### With Nix (Recommended)

The easiest way to run integration tests is with Nix, which automatically provides PostgreSQL:

```bash
nix build .#nexus-integration-tests
```

Or run as a check:

```bash
nix flake check
```

### With Local PostgreSQL

If you have PostgreSQL installed locally, you can run the tests using the provided script:

```bash
./run-integration-tests.sh
```

This script will:
1. Create a temporary PostgreSQL instance
2. Initialize the test database
3. Run the integration tests
4. Clean up automatically

### Manual Setup

If you want to run tests against an existing PostgreSQL instance:

1. Set up environment variables:
```bash
export POSTGRES_HOST=localhost
export POSTGRES_PORT=5432
export POSTGRES_DB=nexus_test
export POSTGRES_USER=postgres
export POSTGRES_PASSWORD=yourpassword
```

2. Create the test database:
```bash
createdb nexus_test
```

3. Run the integration tests:
```bash
clojure -M:integration
```

Or run only integration tests using eftest:
```bash
clojure -M:test -n nexus.integration-test
```

## Test Coverage

The integration tests cover:

### Authentication
- ✓ Health check endpoint (unauthenticated)
- ✓ HMAC signature validation
- ✓ Rejection of invalid signatures
- ✓ Rejection of missing authentication

### IPv4 Operations
- ✓ Update IPv4 address
- ✓ Retrieve IPv4 address
- ✓ Database persistence verification

### IPv6 Operations
- ✓ Update IPv6 address
- ✓ Retrieve IPv6 address
- ✓ Database persistence verification

### SSHFP Operations
- ✓ Update SSH fingerprints
- ✓ Retrieve SSH fingerprints
- ✓ Multiple fingerprint handling

### Batch Operations
- ✓ Update multiple record types in single request
- ✓ Verify all records persisted correctly

### Database Features
- ✓ SOA serial auto-increment on record changes
- ✓ Database trigger functionality
- ✓ Transaction handling

## Architecture

The integration tests use:

- **PostgreSQL**: Real database instance (temporary or existing)
- **PowerDNS Schema**: Standard PowerDNS tables plus custom extensions
- **Ring Mock**: For HTTP request simulation
- **next.jdbc**: Direct database queries for verification
- **HMAC Authentication**: Full signature generation and validation

## Schema

Tests initialize the following schema:

1. **PowerDNS Standard Tables**:
   - `domains` - DNS zones
   - `records` - DNS records (A, AAAA, SSHFP, etc.)
   - `supermasters`, `comments`, `domainmetadata`, `cryptokeys`, `tsigkeys`

2. **Custom Tables**:
   - `challenges` - ACME DNS-01 validation records

3. **Triggers**:
   - `update_zone_serial_on_change` - Auto-increments SOA serial

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_HOST` | `localhost` | PostgreSQL server hostname |
| `POSTGRES_PORT` | `5432` | PostgreSQL server port |
| `POSTGRES_DB` | `nexus_test` | Test database name |
| `POSTGRES_USER` | `postgres` | Database user |
| `POSTGRES_PASSWORD` | `` | Database password (empty for local) |

## Troubleshooting

### PostgreSQL Connection Refused

If you see connection errors:
1. Ensure PostgreSQL is running: `pg_isready`
2. Check the port is correct
3. Verify the user has access rights

### Schema Initialization Fails

If schema initialization fails:
1. Drop the test database: `dropdb nexus_test`
2. Recreate it: `createdb nexus_test`
3. Check PostgreSQL logs for errors

### Tests Hang

If tests hang:
1. Check for zombie PostgreSQL processes: `ps aux | grep postgres`
2. Kill them if needed: `pkill -9 postgres`
3. Clean up temp directories: `rm -rf /tmp/nexus-pg-*`

## Adding New Tests

To add new integration tests:

1. Add test functions to `integration_test.clj`
2. Use the `*app*` dynamic var to send requests
3. Use `*db-config*` to query the database directly
4. Use `*test-keys*` for authentication
5. Use `make-authenticated-request` helper for signed requests

Example:

```clojure
(deftest test-my-new-feature
  (testing "My new feature works correctly"
    (let [domain "test.example.com"
          hostname "testhost"
          key-str (get *test-keys* hostname)
          path "/api/v2/domain/test.example.com/host/testhost/myfeature"]
      
      ;; Make request
      (let [req (make-authenticated-request :put path hostname key-str :body "data")
            response (*app* req)]
        (is (= 200 (:status response))))
      
      ;; Verify in database
      (let [results (jdbc/execute! *db-config* ["SELECT ..."])]
        (is (= expected-value (:column (first results))))))))
```

## CI/CD Integration

Integration tests are automatically run in CI via `nix flake check`, which includes:

1. `nexus-tests` - Unit tests (fast, no external deps)
2. `nexus-integration-tests` - Integration tests (with PostgreSQL)

Both must pass for the build to succeed.
