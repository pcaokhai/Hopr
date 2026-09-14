#!/usr/bin/env bash
# k8s/deploy.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if ! kind get clusters | grep -q '^hopr$'; then
  kind create cluster --config k8s/kind-config.yaml
fi

kubectl create namespace hopr --dry-run=client -o yaml | kubectl apply -f -

helm repo add bitnami https://charts.bitnami.com/bitnami >/dev/null 2>&1 || true
helm repo update

# Redis Cluster's Bitnami image is multi-arch and works fine; ScyllaDB is
# deployed by this repo's own chart (see k8s/hopr-chart/templates/scylladb.yaml).
helm upgrade --install hopr-redis bitnami/redis-cluster \
  --namespace hopr \
  --set cluster.nodes=6 \
  --set usePassword=false \
  --set image.repository=bitnamilegacy/redis-cluster \
  --set updateJob.image.repository=bitnamilegacy/kubectl \
  --set sysctlImage.repository=bitnamilegacy/os-shell \
  --set volumePermissions.image.repository=bitnamilegacy/os-shell \
  --set metrics.image.repository=bitnamilegacy/redis-exporter \
  --wait --timeout 5m

kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml

kubectl wait --namespace ingress-nginx \
  --for=condition=ready pod \
  --selector=app.kubernetes.io/component=controller \
  --timeout=180s

./k8s/build-and-load.sh

# shortener/resolver open a CqlSession against keyspace hopr at boot, so on a fresh
# install the schema has to exist before their Deployments do: install everything
# else first, migrate through a port-forward to the seed node, then enable the two
# URL services. On a re-run the release already exists and they are left running.
if ! helm status hopr --namespace hopr >/dev/null 2>&1; then
  helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr --set urlServices.enabled=false
fi

kubectl rollout status statefulset/hopr-scylladb -n hopr --timeout=600s
kubectl port-forward -n hopr hopr-scylladb-0 9042:9042 >/dev/null 2>&1 &
PF_PID=$!
trap 'kill "$PF_PID" 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  (exec 3<>/dev/tcp/127.0.0.1/9042) 2>/dev/null && break
  sleep 1
done
./gradlew :db-migration:migrateScylla -Pscylla.contactPoint=127.0.0.1:9042
kill "$PF_PID" 2>/dev/null || true; trap - EXIT

helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr

kubectl rollout status deployment/config-server -n hopr --timeout=120s
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
kubectl rollout status deployment/shortener-service -n hopr --timeout=120s
kubectl rollout status deployment/resolver-service -n hopr --timeout=120s

echo "Hopr is up. Try: curl -X POST http://localhost:8888/shorten -d '{\"longUrl\":\"https://example.com\"}' -H 'Content-Type: application/json'"
