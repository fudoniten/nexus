{
  description = "Nexus DDNS System - Monorepo";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    fudo-clojure = {
      url = "github:fudoniten/fudo-clojure";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, fudo-clojure, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        inherit (helpers.legacyPackages."${system}")
          mkClojureBin mkClojureLib mkClojureTests;

        # Local Clojure libraries (no longer fetched from git!)
        # Use preppedSrc instead of JAR for deps-lock to get transitive dependencies
        cljLibs = {
          "org.fudo/fudo-clojure" =
            fudo-clojure.packages."${system}".fudo-clojure.preppedSrc;
        };

      in {
        packages = rec {
          default = nexus-client;

          # Crypto library (for external consumers if needed)
          nexus-crypto = mkClojureLib {
            name = "org.fudo/nexus.crypto";
            src = ./.;
          };

          # Key generation utility
          nexus-keygen = mkClojureBin {
            name = "org.fudo/nexus-keygen";
            primaryNamespace = "nexus.keygen";
            src = ./.;
            inherit cljLibs;
          };

          # DDNS Client
          nexus-client = mkClojureBin {
            name = "org.fudo/nexus-client";
            primaryNamespace = "nexus.client.cli";
            src = ./.;
            inherit cljLibs;
          };

          # DDNS Server
          nexus-server = mkClojureBin {
            name = "org.fudo/nexus-server";
            primaryNamespace = "nexus.server.cli";
            src = ./.;
            inherit cljLibs;
          };
        };

        devShells = rec {
          default = updateDeps;

          # Update deps-lock.json (without test dependencies)
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.legacyPackages."${system}";
              [ (updateClojureDeps { deps = cljLibs; }) ];
          };

          # Update deps-lock.json including test dependencies
          updateDepsWithTests = pkgs.mkShell {
            buildInputs = with helpers.legacyPackages."${system}";
              [
                (updateClojureDeps {
                  deps = cljLibs;
                  aliases = [ "test" ];
                })
              ];
          };

          # Integration test environment with PostgreSQL
          integration-tests = pkgs.mkShell {
            name = "nexus-integration-tests";
            buildInputs = with pkgs; [ clojure postgresql bash ];
            shellHook = ''
                echo "🧪 Integration test environment ready!"
              echo ""
              echo "To run integration tests:"
              echo "  ./run-integration-tests.sh"
              echo ""
              echo "Or manually:"
              echo "  export POSTGRES_HOST=localhost POSTGRES_PORT=5432"
              echo "  export POSTGRES_DB=nexus_test POSTGRES_USER=postgres"  
              echo "  clojure -M:test -n nexus.integration-test"
            '';
          };
        };

        # Apps that can be run with nix run
        apps = {
          integration-tests = {
            type = "app";
            program = toString (pkgs.writeShellScript "run-integration-tests" ''
              set -euo pipefail

              # Colors for output
              RED='\033[0;31m'
              GREEN='\033[0;32m'
              YELLOW='\033[1;33m'
              NC='\033[0m'

              log_info() { echo -e "''${GREEN}[INFO]''${NC} $*"; }
              log_warn() { echo -e "''${YELLOW}[WARN]''${NC} $*"; }
              log_error() { echo -e "''${RED}[ERROR]''${NC} $*"; }

              # Create temporary directory for PostgreSQL
              PGDATA=$(${pkgs.coreutils}/bin/mktemp -d -t nexus-pg-XXXXXX)
              PGPORT=''${POSTGRES_PORT:-15432}
              PGDATABASE="nexus_test"
              PGUSER="$USER"

              cleanup() {
                local exit_code=$?
                log_info "Cleaning up..."
                if [ -n "''${PG_PID:-}" ]; then
                  log_info "Stopping PostgreSQL (PID: $PG_PID)..."
                  kill "$PG_PID" 2>/dev/null || true
                  wait "$PG_PID" 2>/dev/null || true
                fi
                if [ -d "$PGDATA" ]; then
                  log_info "Removing temporary data directory: $PGDATA"
                  ${pkgs.coreutils}/bin/rm -rf "$PGDATA"
                fi
                exit $exit_code
              }

              trap cleanup EXIT INT TERM

              # Initialize PostgreSQL database
              log_info "Initializing PostgreSQL in $PGDATA..."
              ${pkgs.postgresql}/bin/initdb -D "$PGDATA" --no-locale --encoding=UTF8 -U "$PGUSER" > /dev/null

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
              ${pkgs.postgresql}/bin/postgres -D "$PGDATA" > "$PGDATA/postgres.log" 2>&1 &
              PG_PID=$!

              # Wait for PostgreSQL to be ready
              log_info "Waiting for PostgreSQL to be ready..."
              for i in {1..30}; do
                if ${pkgs.postgresql}/bin/psql -h localhost -p "$PGPORT" -U "$PGUSER" -d postgres -c "SELECT 1" > /dev/null 2>&1; then
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
              ${pkgs.postgresql}/bin/createdb -h localhost -p "$PGPORT" -U "$PGUSER" "$PGDATABASE"

              # Export environment variables for tests
              export POSTGRES_HOST=localhost
              export POSTGRES_PORT=$PGPORT
              export POSTGRES_DB=$PGDATABASE
              export POSTGRES_USER=$PGUSER
              export POSTGRES_PASSWORD=""

              # Run integration tests from the project directory
              cd ${./.}
              log_info "Running integration tests..."
              if ${pkgs.clojure}/bin/clojure -M:integration; then
                log_info "✓ All integration tests passed!"
                exit 0
              else
                log_error "✗ Integration tests failed"
                exit 1
              fi
            '');
          };
        };

        # Run tests with eftest using deps-lock.json
        checks = {
          # Unit tests (existing)
          nexus-tests = mkClojureTests {
            name = "nexus";
            src = ./.;
            testAlias = "test";
            inherit cljLibs;
          };
        };
      }) // {
        # NixOS modules now reference packages from same flake
        nixosModules = {
          nexus-client = import ./nix/client.nix self.packages;
          nexus-powerdns = import ./nix/powerdns.nix;
          nexus-server = import ./nix/server.nix self.packages;
        };
      };
}
