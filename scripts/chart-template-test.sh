#!/usr/bin/env bash
# Asserts that `helm template` renders the disruption/scheduling resources the chart is
# supposed to produce: a PodDisruptionBudget per request-serving workload, zone-keyed
# topologySpreadConstraints on those workloads' pod specs, and the canary Rollouts
# (plus their HPA scaleTargetRefs) those two services are deployed as.
#
# This checks CONFIGURATION ONLY. It cannot and does not test runtime behaviour: whether
# the eviction API actually honours the budget during a node drain, or whether the
# scheduler actually spreads replicas across zones, is observable only on a real
# multi-node, multi-zone cluster. See k8s/README.md.
#
# No YAML parser: the repo's Python has no PyYAML and yq is not a dependency here, so
# each field is looked up independently inside its own block rather than by matching a
# fixed window of literal output (which would break on any benign reordering).
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v helm >/dev/null 2>&1; then
  echo "SKIP: helm not installed"
  exit 0
fi

render() { helm template hopr ./k8s/hopr-chart --set tls.crt=dummy --set tls.key=dummy "$@"; }

fail() { echo "FAIL: $1" >&2; exit 1; }

# The `---`-separated document containing the given line.
doc() { awk -v pat="$1" 'BEGIN{RS="\n---\n"} $0 ~ pat {print; exit}'; }

# The indented block introduced by the given key, plus the key's own line.
block() { awk -v key="$1" '
  $0 ~ "^( *)" key "$" { match($0, /^ */); ind = RLENGTH; print; inb = 1; next }
  inb { match($0, /^ */); if (RLENGTH <= ind && $0 !~ /^ *$/) exit; print }'; }

# Value of `key:` inside a block, whitespace- and list-marker-normalised.
value() { sed -n "s/^[ -]*$1: *//p"; }

expect() { [ "$2" = "$3" ] || fail "$1: got '$2', want '$3'"; }

for svc in shortener-service resolver-service; do
  pdb=$(render -s templates/pdb.yaml | doc "name: $svc\n")
  [ -n "$pdb" ] || fail "no PodDisruptionBudget rendered for $svc"
  expect "PodDisruptionBudget/$svc kind" "$(printf '%s' "$pdb" | value kind)" "PodDisruptionBudget"
  expect "PodDisruptionBudget/$svc maxUnavailable" "$(printf '%s' "$pdb" | value maxUnavailable)" "1"
  expect "PodDisruptionBudget/$svc selector" \
    "$(printf '%s' "$pdb" | block 'selector:' | value app)" "$svc"

  tsc=$(render -s "templates/$svc.yaml" | block 'topologySpreadConstraints:')
  [ -n "$tsc" ] || fail "no topologySpreadConstraints rendered on $svc"
  expect "topologySpreadConstraints on $svc: maxSkew" "$(printf '%s' "$tsc" | value maxSkew)" "1"
  expect "topologySpreadConstraints on $svc: topologyKey" \
    "$(printf '%s' "$tsc" | value topologyKey)" "topology.kubernetes.io/zone"
  expect "topologySpreadConstraints on $svc: whenUnsatisfiable" \
    "$(printf '%s' "$tsc" | value whenUnsatisfiable)" "ScheduleAnyway"
  expect "topologySpreadConstraints on $svc: labelSelector" \
    "$(printf '%s' "$tsc" | block 'labelSelector:' | value app)" "$svc"

  echo "ok: $svc PodDisruptionBudget + topologySpreadConstraints"
done

# The versioned API surface: every /v1 route has to reach shortener-service through the
# ingress, and the API keys have to arrive as `<digest>:<owner-id>` pairs -- a chart that
# still renders the old unversioned path or the old hash-only variable would deploy an API
# nothing can reach with the keys nobody is scoped by.
ingress=$(render -s templates/ingress.yaml)
grep -q 'path: /v1$' <<<"$ingress" || fail "ingress does not route /v1 to the API"
if grep -q 'path: /shorten$' <<<"$ingress"; then fail "ingress still routes the removed unversioned /shorten"; fi

configmap=$(render -s templates/configmap.yaml --set config.shortenerApiKeyOwners=abc123:owner-a)
grep -q 'SHORTENER_API_KEY_OWNERS: "abc123:owner-a"' <<<"$configmap" \
  || fail "ConfigMap does not carry the key-to-owner pairs: $configmap"

echo "ok: ingress /v1 routing + SHORTENER_API_KEY_OWNERS ConfigMap entry"

# The progressive-delivery surface: both hot-path services must render as Argo Rollouts with
# the canary steps, and their HPAs must target the Rollout. An HPA left pointing at
# `kind: Deployment` would silently scale nothing once the Deployment is gone.
for svc in shortener-service resolver-service; do
  # helm sorts rendered documents by kind, so select the document by kind, not by position.
  rollout=$(render -s "templates/$svc.yaml" | doc "kind: Rollout")
  [ -n "$rollout" ] || fail "$svc does not render a Rollout"
  grep -q "name: $svc$" <<<"$rollout" || fail "Rollout is not named $svc"
  canary=$(printf '%s' "$rollout" | block 'canary:')
  # Surge-first: a canary step must never take a stable replica down before its
  # replacement is Ready, at any replica count the HPA has scaled to.
  expect "$svc canary maxUnavailable" "$(printf '%s' "$canary" | value maxUnavailable)" "0"
  # The schedule is an ordered sequence, not a set: a `setWeight: 100` first would render a
  # meaningless canary while still containing every string below.
  steps=$(printf '%s' "$rollout" | block 'steps:' | sed '1d;s/[ -]*//g' | paste -sd, -)
  expect "$svc canary steps" "$steps" \
    "setWeight:20,pause:,duration:60s,setWeight:50,pause:,duration:60s,setWeight:100"
  if render -s "templates/$svc.yaml" | grep -q 'kind: Deployment'; then
    fail "$svc still renders a Deployment"
  fi

  hpa=$(render -s templates/hpa.yaml | doc "name: $svc\n")
  expect "HPA/$svc scaleTargetRef kind" \
    "$(printf '%s' "$hpa" | block 'scaleTargetRef:' | value kind)" "Rollout"

  echo "ok: $svc Rollout canary steps + HPA scaleTargetRef"
done
