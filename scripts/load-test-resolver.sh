#!/usr/bin/env bash
# Load-tests the resolver's redirect path (GET /{shortKey}) against a real running stack, using
# k6 (resolver-service/loadtest/resolve-redirect.js). Not part of scripts/run-tests.sh / CI:
# load tests measure latency under load rather than pass/fail correctness, and this one needs
# the full docker-compose stack (Scylla, Redis cluster, keygen/shortener/resolver services) up
# for tens of seconds, which doesn't fit a per-commit run. Intended cadence: run manually before
# a release, or whenever a change could plausibly move redirect latency (cache config, resolver
# code, Redis/Scylla topology).
#
# Requires docker and a local .env / .env.secrets (see DOCKER_COMPOSE_GUIDE.md step 2).
set -euo pipefail
cd "$(dirname "$0")/.."

LINKS=${LOAD_TEST_LINKS:-50}
API_KEY=${SHORTENER_API_KEY:-hopr-local-dev-key}
SHORTENER_URL=http://localhost:8080
RESOLVER_URL=http://localhost:8083

echo "==> bringing up config-server, keygen-service, shortener-service, resolver-service"
docker compose up -d config-server keygen-service shortener-service resolver-service

wait_for() {
  local url=$1 name=$2
  for _ in $(seq 1 60); do
    curl -sf -o /dev/null "$url" && { echo "$name is up"; return 0; }
    sleep 2
  done
  echo "FAIL: $name never became healthy at $url" >&2
  exit 1
}
wait_for "$SHORTENER_URL/actuator/health" shortener-service
wait_for "$RESOLVER_URL/actuator/health" resolver-service

echo "==> seeding $LINKS short links via shortener-service"
short_keys=()
for i in $(seq 1 "$LINKS"); do
  response=$(curl -sf -X POST "$SHORTENER_URL/shorten" \
    -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "{\"longUrl\": \"https://example.com/load-test-target-$i\"}")
  short_url=$(echo "$response" | grep -o '"shortUrl":"[^"]*"' | cut -d'"' -f4)
  short_keys+=("${short_url##*/}")
done
short_keys_csv=$(IFS=,; echo "${short_keys[*]}")

echo "==> priming the read-through cache with one resolve per link"
for key in "${short_keys[@]}"; do
  curl -s -o /dev/null "$RESOLVER_URL/$key"
done

echo "==> running k6 (20 VUs, ~40s) against $RESOLVER_URL"
docker run --rm --network host \
  -e RESOLVER_URL="$RESOLVER_URL" -e SHORT_KEYS="$short_keys_csv" \
  -v "$(pwd)/resolver-service/loadtest:/scripts:ro" \
  grafana/k6:latest run /scripts/resolve-redirect.js
