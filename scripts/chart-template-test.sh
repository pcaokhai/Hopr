#!/usr/bin/env bash
# Asserts that `helm template` renders the disruption/scheduling resources the chart is
# supposed to produce: a PodDisruptionBudget per request-serving Deployment, and
# zone-keyed topologySpreadConstraints on those Deployments' pod specs.
#
# This checks CONFIGURATION ONLY. It cannot and does not test runtime behaviour: whether
# the eviction API actually honours the budget during a node drain, or whether the
# scheduler actually spreads replicas across zones, is observable only on a real
# multi-node, multi-zone cluster. See k8s/README.md.
#
# Plain text matching rather than a YAML parser: the repo's Python has no PyYAML and
# pulling a dependency in for two blocks of literal output is not worth it.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v helm >/dev/null 2>&1; then
  echo "SKIP: helm not installed"
  exit 0
fi

render() { helm template hopr ./k8s/hopr-chart --set tls.crt=dummy --set tls.key=dummy "$@"; }

fail() { echo "FAIL: $1" >&2; exit 1; }

# `grep -A` window from the named anchor, whitespace-normalised for comparison.
block() { grep -A"$2" -- "$1" | sed 's/[[:space:]]\{1,\}/ /g;s/^ //;s/ $//'; }

for svc in shortener-service resolver-service; do
  pdb=$(render -s templates/pdb.yaml | block "name: $svc" 6)
  expected="name: $svc
namespace: hopr
spec:
maxUnavailable: 1
selector:
matchLabels:
app: $svc"
  [ "$pdb" = "$expected" ] || fail "PodDisruptionBudget/$svc: got
$pdb
want
$expected"

  tsc=$(render -s "templates/$svc.yaml" | block "topologySpreadConstraints:" 6)
  expected="topologySpreadConstraints:
- maxSkew: 1
topologyKey: topology.kubernetes.io/zone
whenUnsatisfiable: ScheduleAnyway
labelSelector:
matchLabels:
app: $svc"
  [ "$tsc" = "$expected" ] || fail "topologySpreadConstraints on $svc: got
$tsc
want
$expected"

  echo "ok: $svc PodDisruptionBudget + topologySpreadConstraints"
done
