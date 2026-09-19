#!/usr/bin/env bash
# k8s/deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

if ! kind get clusters | grep -q '^hopr$'; then
  kind create cluster --config k8s/kind-config.yaml
fi

# The chart ships no default key (shortener-service refuses to start on an empty hash list),
# so mint a local development key once and reuse it on every re-run -- a fresh key each time
# would invalidate the one already installed in the cluster.
KEY_FILE="$SCRIPT_DIR/.dev-api-key"
[ -f "$KEY_FILE" ] || (umask 077; openssl rand -hex 32 > "$KEY_FILE")
chmod 600 "$KEY_FILE"
SHORTEN_API_KEY=$(tr -d '\n' < "$KEY_FILE")
SHORTEN_API_KEY_HASH=$(printf %s "$SHORTEN_API_KEY" | shasum -a 256 | cut -d' ' -f1)

# Self-signed TLS material for the ingress, minted once and reused (regenerating on every
# run would hand clients a different cert each time). DEV ONLY -- nothing trusts it; a real
# deployment issues this Secret through cert-manager instead. Both files are gitignored.
CRT_FILE="$SCRIPT_DIR/.dev-tls.crt"
KEY_FILE_TLS="$SCRIPT_DIR/.dev-tls.key"
if [ ! -f "$CRT_FILE" ] || [ ! -f "$KEY_FILE_TLS" ]; then
  openssl_err=$( (umask 077; openssl req -x509 -newkey rsa:2048 -nodes -days 825 -sha256 \
    -keyout "$KEY_FILE_TLS" -out "$CRT_FILE" \
    -subj "/CN=localhost/O=Hopr local development (self-signed, do not trust)" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>&1 >/dev/null) ) || {
    echo "openssl failed to mint the dev certificate:" >&2
    echo "$openssl_err" >&2
    exit 1
  }
  chmod 644 "$CRT_FILE"
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

# Serve HTTPS on 8443 inside the node so the host port kind maps (k8s/kind-config.yaml) and
# the port nginx names in its HTTP->HTTPS redirect are the same number: the redirect target
# port comes from --https-port, and with the stock 443 it would point at a port no client can
# reach here. The admission webhook owns 8443 by default, so it moves to 8444.
kubectl -n ingress-nginx patch deployment ingress-nginx-controller --type json -p '[
  {"op":"replace","path":"/spec/template/spec/containers/0/args/5","value":"--validating-webhook=:8444"},
  {"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--https-port=8443"},
  {"op":"replace","path":"/spec/template/spec/containers/0/ports/1/containerPort","value":8443},
  {"op":"replace","path":"/spec/template/spec/containers/0/ports/1/hostPort","value":8443},
  {"op":"replace","path":"/spec/template/spec/containers/0/ports/2/containerPort","value":8444}
]'

# use-port-in-redirects makes the redirect spell out that port (https://localhost:8443/...)
# instead of dropping it and implying 443.
#
# The controller's default HSTS max-age is one year, and it answers for `localhost`, which
# would pin every http://localhost:PORT on the developer's machine for that long. Same dev
# value as the Compose gateway (api-gateway/security-headers.conf); HSTS itself stays on.
kubectl -n ingress-nginx patch configmap ingress-nginx-controller --type merge \
  -p '{"data":{"hsts-max-age":"300","use-port-in-redirects":"true"}}'

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
  helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr --set urlServices.enabled=false \
    --set shortenApiKey="$SHORTEN_API_KEY" --set config.shortenerApiKeyHashes="$SHORTEN_API_KEY_HASH" \
    --set-file tls.crt="$CRT_FILE" --set-file tls.key="$KEY_FILE_TLS"
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

helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr \
  --set shortenApiKey="$SHORTEN_API_KEY" --set config.shortenerApiKeyHashes="$SHORTEN_API_KEY_HASH" \
  --set-file tls.crt="$CRT_FILE" --set-file tls.key="$KEY_FILE_TLS"

kubectl rollout status deployment/config-server -n hopr --timeout=120s
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
kubectl rollout status deployment/shortener-service -n hopr --timeout=120s
kubectl rollout status deployment/resolver-service -n hopr --timeout=120s
kubectl rollout status deployment/frontend -n hopr --timeout=120s

# -k: the certificate above is self-signed, so no client trusts it without an override.
echo "Hopr is up. Try: curl -k -X POST https://localhost:8443/shorten -d '{\"longUrl\":\"https://example.com\"}' -H 'Content-Type: application/json' -H \"X-API-Key: $SHORTEN_API_KEY\""
