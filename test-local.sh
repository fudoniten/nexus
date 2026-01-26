#!/usr/bin/env bash
# Quick local test script to verify integration tests work
# This doesn't require Nix and can be run during development

set -euo pipefail

echo "🧪 Running local integration test check..."
echo ""

# Check for PostgreSQL
if ! command -v psql &> /dev/null; then
    echo "❌ PostgreSQL is not installed or not in PATH"
    echo "   Please install PostgreSQL or use: nix build .#nexus-integration-tests"
    exit 1
fi

# Check for Clojure
if ! command -v clojure &> /dev/null; then
    echo "❌ Clojure CLI is not installed or not in PATH"
    echo "   Please install Clojure or use: nix build .#nexus-integration-tests"
    exit 1
fi

echo "✓ PostgreSQL found: $(psql --version | head -1)"
echo "✓ Clojure found: $(clojure --version 2>&1 | head -1)"
echo ""

# Run the integration test script
if [ -x ./run-integration-tests.sh ]; then
    echo "🚀 Running integration tests..."
    echo ""
    ./run-integration-tests.sh
else
    echo "❌ run-integration-tests.sh not found or not executable"
    exit 1
fi
