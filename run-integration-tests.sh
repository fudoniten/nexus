#!/usr/bin/env bash
# Integration test runner with PostgreSQL
# This script starts a temporary PostgreSQL instance and runs integration tests against it

set -euo pipefail

# Colors for output
RED='\033[0:31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

# Create temporary directory for PostgreSQL
PGDATA=$(mktemp -d -t nexus-pg-XXXXXX)
PGPORT=${POSTGRES_PORT:-15432}
PGDATABASE="nexus_test"
PGUSER="postgres"

cleanup() {
    local exit_code=$?
    log_info "Cleaning up..."
    if [ -n "${PG_PID:-}" ]; then
        log_info "Stopping PostgreSQL (PID: $PG_PID)..."
        kill "$PG_PID" 2>/dev/null || true
        wait "$PG_PID" 2>/dev/null || true
    fi
    if [ -d "$PGDATA" ]; then
        log_info "Removing temporary data directory: $PGDATA"
        rm -rf "$PGDATA"
    fi
    exit $exit_code
}

trap cleanup EXIT INT TERM

# Initialize PostgreSQL database
log_info "Initializing PostgreSQL in $PGDATA..."
initdb -D "$PGDATA" --no-locale --encoding=UTF8 > /dev/null

# Configure PostgreSQL for testing
cat >> "$PGDATA/postgresql.conf" <<EOF
listen_addresses = 'localhost'
port = $PGPORT
unix_socket_directories = '$PGDATA'
max_connections = 20
shared_buffers = 128MB
fsync = off
synchronous_commit = off
full_page_writes = off
EOF

# Start PostgreSQL
log_info "Starting PostgreSQL on port $PGPORT..."
postgres -D "$PGDATA" > "$PGDATA/postgres.log" 2>&1 &
PG_PID=$!

# Wait for PostgreSQL to be ready
log_info "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if psql -h localhost -p "$PGPORT" -U "$PGUSER" -d postgres -c "SELECT 1" > /dev/null 2>&1; then
        log_info "PostgreSQL is ready!"
        break
    fi
    if [ $i -eq 30 ]; then
        log_error "PostgreSQL failed to start within 30 seconds"
        log_error "PostgreSQL log:"
        cat "$PGDATA/postgres.log"
        exit 1
    fi
    sleep 1
done

# Create test database
log_info "Creating test database..."
createdb -h localhost -p "$PGPORT" -U "$PGUSER" "$PGDATABASE"

# Export environment variables for tests
export POSTGRES_HOST=localhost
export POSTGRES_PORT=$PGPORT
export POSTGRES_DB=$PGDATABASE
export POSTGRES_USER=$PGUSER
export POSTGRES_PASSWORD=""

# Run integration tests
log_info "Running integration tests..."
if clojure -M:test -n nexus.integration-test; then
    log_info "✓ All integration tests passed!"
    exit 0
else
    log_error "✗ Integration tests failed"
    exit 1
fi
