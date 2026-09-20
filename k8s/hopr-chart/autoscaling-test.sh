#!/usr/bin/env bash
# Proves replicas/resources/HPA targets actually come from values.yaml -- i.e. that the
# chart isn't silently ignoring `--set` the way the old hardcoded `replicas: 1` did.
#
# Renders the shortener-service Deployment and its HorizontalPodAutoscaler twice, once with
# chart defaults and once with `--set` overrides, and asserts the rendered YAML picks up
# every override. Requires helm and docker (for the YAML-to-JSON conversion), no live
# cluster needed.
set -euo pipefail

CHART="$(cd "$(dirname "$0")" && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# tls.crt/tls.key and shortenApiKey are `required`d by unrelated templates that helm still
# evaluates even when --show-only narrows the output, so every render needs dummy values.
echo dummy-cert > "$TMPDIR/tls.crt"
echo dummy-key > "$TMPDIR/tls.key"
COMMON_SET=(--set shortenApiKey=x --set config.shortenerApiKeyOwners=abc:owner-a
  --set-file "tls.crt=$TMPDIR/tls.crt" --set-file "tls.key=$TMPDIR/tls.key")

render() {
  helm template "$CHART" "${COMMON_SET[@]}" "$@" \
    --show-only templates/shortener-service.yaml --show-only templates/hpa.yaml \
    | docker run --rm -i mikefarah/yq -o=json -N ea '[.]'
}

DEFAULT_JSON="$(render)"
OVERRIDE_JSON="$(render \
  --set shortenerService.replicaCount=4 \
  --set shortenerService.resources.requests.cpu=999m \
  --set shortenerService.resources.limits.memory=2048Mi \
  --set shortenerService.autoscaling.minReplicas=2 \
  --set shortenerService.autoscaling.maxReplicas=9 \
  --set shortenerService.autoscaling.targetCPUUtilizationPercentage=55)"

python3 -c '
import json, sys

default_docs = json.loads(sys.argv[1])
override_docs = json.loads(sys.argv[2])


def find(docs, kind):
    return next(d for d in docs if d["kind"] == kind)


d_dep, o_dep = find(default_docs, "Deployment"), find(override_docs, "Deployment")
d_hpa, o_hpa = find(default_docs, "HorizontalPodAutoscaler"), find(override_docs, "HorizontalPodAutoscaler")

checks = [
    ("Deployment.replicas", d_dep["spec"]["replicas"], 1, o_dep["spec"]["replicas"], 4),
    (
        "container.resources.requests.cpu",
        d_dep["spec"]["template"]["spec"]["containers"][0]["resources"]["requests"]["cpu"],
        "250m",
        o_dep["spec"]["template"]["spec"]["containers"][0]["resources"]["requests"]["cpu"],
        "999m",
    ),
    (
        "container.resources.limits.memory",
        d_dep["spec"]["template"]["spec"]["containers"][0]["resources"]["limits"]["memory"],
        "512Mi",
        o_dep["spec"]["template"]["spec"]["containers"][0]["resources"]["limits"]["memory"],
        "2048Mi",
    ),
    ("HPA.minReplicas", d_hpa["spec"]["minReplicas"], 1, o_hpa["spec"]["minReplicas"], 2),
    ("HPA.maxReplicas", d_hpa["spec"]["maxReplicas"], 5, o_hpa["spec"]["maxReplicas"], 9),
    (
        "HPA.targetCPUUtilization",
        d_hpa["spec"]["metrics"][0]["resource"]["target"]["averageUtilization"],
        70,
        o_hpa["spec"]["metrics"][0]["resource"]["target"]["averageUtilization"],
        55,
    ),
]

failures = []
for name, default_val, want_default, override_val, want_override in checks:
    if default_val != want_default:
        failures.append("%s default=%r, expected %r" % (name, default_val, want_default))
    if override_val != want_override:
        failures.append("%s override=%r, expected %r" % (name, override_val, want_override))
    if default_val == override_val:
        failures.append("%s did not change under --set override (stuck at %r)" % (name, default_val))

if failures:
    sys.exit("FAIL:\n  " + "\n  ".join(failures))
print("PASS: %d chart fields render from values.yaml and respond to --set overrides" % len(checks))
' "$DEFAULT_JSON" "$OVERRIDE_JSON"
