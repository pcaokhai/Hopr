#!/usr/bin/env bash
# k8s/deploy.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

if kind get clusters | grep -q '^hopr$'; then
  # extraPortMappings are fixed when the cluster is created, so a cluster made before the
  # HTTPS mapping moved to node port 8443 keeps the old one and host 8443 reaches nothing.
  # Read the configured bindings, which survive a stopped node, not the live ones.
  if ! docker inspect -f '{{json .HostConfig.PortBindings}}' hopr-control-plane 2>/dev/null \
    | grep -q '"8443/tcp"'; then
    echo "The existing 'hopr' kind cluster does not map host port 8443 to node port 8443," >&2
    echo "so HTTPS would be unreachable. kind cannot change that after creation:" >&2
    echo "  kind delete cluster --name hopr && ./k8s/deploy.sh" >&2
    exit 1
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' hopr-control-plane)" != "true" ]; then
    # The container is running long before the kubelet and API server are, so everything
    # below would fail with "connection refused" without this wait.
    docker start hopr-control-plane >/dev/null
    for _ in $(seq 1 60); do
      kubectl wait --for=condition=Ready node --all --timeout=10s >/dev/null 2>&1 && break
      sleep 2
    done
    kubectl wait --for=condition=Ready node --all --timeout=60s
  fi
else
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
# Each accepted key is configured with the owner it identifies; the cluster gets one key, so
# one owner. A second `<digest>:<owner>` pair is comma separated -- and a comma also separates
# assignments in `helm --set`, so pairs are passed below with --set-string and an escaped
# comma (`aaa:owner-a\,bbb:owner-b`), which helm reads as one value rather than two keys.
SHORTEN_API_KEY_OWNERS="$SHORTEN_API_KEY_HASH:local-dev"

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

# Re-applying the upstream manifest over an already-patched controller merges its webhook
# port (8443) back in beside the patched one and the Deployment is rejected for the duplicate
# port name, so install and patch it only once.
if ! kubectl -n ingress-nginx get deployment ingress-nginx-controller \
  -o jsonpath='{.spec.template.spec.containers[0].args}' 2>/dev/null \
  | grep -q -- '--default-ssl-certificate=hopr/hopr-tls'; then
  kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.11.3/deploy/static/provider/kind/deploy.yaml

  # Both Ingresses have host-less rules, so every request lands on the controller's catch-all
  # server, and a catch-all server takes its certificate from --default-ssl-certificate, never
  # from an Ingress `tls:` block. Without this the cluster would serve the controller's own
  # fake certificate and the minted hopr-tls pair would sit unused.
  #
  # Serve HTTPS on 8443 inside the node so the host port kind maps (k8s/kind-config.yaml) and
  # the port nginx names in its HTTP->HTTPS redirect are the same number: the redirect target
  # port comes from --https-port, and with the stock 443 it would point at a port no client can
  # reach here. The admission webhook owns 8443 by default, so it moves to 8444.
  webhook_line=$(kubectl -n ingress-nginx get deployment ingress-nginx-controller \
    -o jsonpath='{range .spec.template.spec.containers[0].args[*]}{@}{"\n"}{end}' \
    | grep -n -- '--validating-webhook=' | cut -d: -f1)
  if [ -z "$webhook_line" ]; then
    echo "ingress-nginx controller exposes no --validating-webhook arg; refusing to patch by index" >&2
    exit 1
  fi
  kubectl -n ingress-nginx patch deployment ingress-nginx-controller --type json -p '[
    {"op":"replace","path":"/spec/template/spec/containers/0/args/'"$((webhook_line - 1))"'","value":"--validating-webhook=:8444"},
    {"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--https-port=8443"},
    {"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--default-ssl-certificate=hopr/hopr-tls"},
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
fi

kubectl -n ingress-nginx rollout status deployment/ingress-nginx-controller --timeout=180s

# Argo Rollouts: a cluster-level controller (own namespace + CRDs), installed from the
# upstream manifest exactly like ingress-nginx above. shortener-service and resolver-service
# are Rollouts rather than Deployments, so without this controller their pods never appear.
# Pinned, and applied only when absent so a re-run does not churn the CRDs.
if ! kubectl get crd rollouts.argoproj.io >/dev/null 2>&1; then
  kubectl create namespace argo-rollouts --dry-run=client -o yaml | kubectl apply -f -
  kubectl apply -n argo-rollouts \
    -f https://github.com/argoproj/argo-rollouts/releases/download/v1.7.2/install.yaml
  kubectl -n argo-rollouts rollout status deployment/argo-rollouts --timeout=180s
fi

./k8s/build-and-load.sh

# shortener/resolver open a CqlSession against keyspace hopr at boot, so on a fresh
# install the schema has to exist before their Deployments do: install everything
# else first, migrate through a port-forward to the seed node, then enable the two
# URL services. On a re-run the release already exists and they are left running.
if ! helm status hopr --namespace hopr >/dev/null 2>&1; then
  helm upgrade --install hopr ./k8s/hopr-chart --namespace hopr --set urlServices.enabled=false \
    --set shortenApiKey="$SHORTEN_API_KEY" --set-string config.shortenerApiKeyOwners="${SHORTEN_API_KEY_OWNERS//,/\\,}" \
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
  --set shortenApiKey="$SHORTEN_API_KEY" --set-string config.shortenerApiKeyOwners="${SHORTEN_API_KEY_OWNERS//,/\\,}" \
  --set-file tls.crt="$CRT_FILE" --set-file tls.key="$KEY_FILE_TLS"

kubectl rollout status deployment/config-server -n hopr --timeout=120s
kubectl rollout status deployment/keygen-service -n hopr --timeout=120s
# A Rollout is not a Deployment, so `kubectl rollout status` cannot read it and the
# `kubectl argo rollouts` plugin is not a prerequisite of this script. Wait on the phase the
# controller writes instead; Healthy means the canary finished (or was skipped on a fresh
# install, which has no previous version to canary against).
# The timeout covers the whole canary, not just pod startup: every pause in
# values.yaml's canary.steps (120s today) plus a startup budget for each successive wave.
kubectl wait --for=jsonpath='{.status.phase}'=Healthy rollout/shortener-service -n hopr --timeout=600s
kubectl wait --for=jsonpath='{.status.phase}'=Healthy rollout/resolver-service -n hopr --timeout=600s
kubectl rollout status deployment/frontend -n hopr --timeout=120s

# The controller reloads when the hopr-tls Secret appears, so on a fresh install the
# catch-all server can still be on the fake certificate for a moment.
for _ in $(seq 1 30); do
  served=$(echo | openssl s_client -connect localhost:8443 2>/dev/null | openssl x509 -noout -subject 2>/dev/null || true)
  case "$served" in *"Hopr local development"*) break ;; esac
  sleep 2
done
case "$served" in
  *"Hopr local development"*) echo "ingress is serving the hopr-tls certificate: $served" ;;
  *) echo "the ingress is not serving the hopr-tls certificate (got: ${served:-nothing})" >&2; exit 1 ;;
esac

# -k: the certificate above is self-signed, so no client trusts it without an override.
echo "Hopr is up. Try: curl -k -X POST https://localhost:8443/v1/shorten -d '{\"longUrl\":\"https://example.com\"}' -H 'Content-Type: application/json' -H \"X-API-Key: $SHORTEN_API_KEY\""
