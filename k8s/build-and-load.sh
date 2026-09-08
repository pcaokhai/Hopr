#!/usr/bin/env bash
# k8s/build-and-load.sh
set -euo pipefail

cd "$(dirname "$0")/.."

./gradlew :config-server:build :keygen-service:build :shortener-service:build :resolver-service:build -x test

for service in config-server keygen-service shortener-service resolver-service; do
  echo "Building image for $service..."
  docker build -t "hopr/$service:local" "$service/"
  kind load docker-image "hopr/$service:local" --name hopr
done

echo "All images built and loaded into kind cluster 'hopr'."
