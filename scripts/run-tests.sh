#!/usr/bin/env bash
# Runs every test suite in this repo, in sequence, failing on the first failure.
# This is the deterministic test command configured in .no-mistakes.yaml.
#
# Backend: Gradle multi-module JUnit suites. shortener-service and resolver-service
# start a real ScyllaDB via Testcontainers, so Docker must be running.
# Frontend: `npm test` in frontend/ (node --test, no install beyond `npm ci`).
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> backend: ./gradlew test"
./gradlew test --console=plain

echo "==> frontend: npm test"
cd frontend
[ -d node_modules ] || npm ci --no-audit --no-fund
npm test
