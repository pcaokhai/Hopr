#!/usr/bin/env bash
# Checks that the rendered Ingress routes each request path to the intended Service.
#
# The rendered manifest is the chart's generated output, so it is parsed into its
# path/pathType/backend triples and evaluated under nginx's location precedence (exact
# first, then regex in order, then longest prefix) -- which is what ingress-nginx turns
# those paths into. Requires helm and docker (for the YAML-to-JSON conversion).
set -euo pipefail

CHART="$(cd "$(dirname "$0")" && pwd)"

helm template "$CHART" --show-only templates/ingress.yaml \
  | docker run --rm -i mikefarah/yq -o=json \
  | python3 -c '
import json, re, sys

expected = {
    "/": "frontend",
    "/api/shorten": "frontend",
    "/_next/static/chunks/main-abc123.js": "frontend",
    "/dashboard": "frontend",
    "/dashboard/my-link": "frontend",
    "/shorten": "shortener-service",
    "/abcd": "resolver-service",
    "/my-link_1": "resolver-service",
}

paths = [
    (p["path"], p["pathType"], p["backend"]["service"]["name"])
    for rule in json.load(sys.stdin)["spec"]["rules"]
    for p in rule["http"]["paths"]
]


def route(request):
    for path, kind, service in paths:
        if kind == "Exact" and path == request:
            return service
    for path, kind, service in paths:
        if kind == "ImplementationSpecific" and re.search(path, request):
            return service
    best = None
    for path, kind, service in paths:
        if kind == "Prefix" and (request == path or request.startswith(path.rstrip("/") + "/")):
            if best is None or len(path) > len(best[0]):
                best = (path, service)
    return best[1] if best else None


failures = [
    "%s -> %s, expected %s" % (request, route(request), want)
    for request, want in expected.items()
    if route(request) != want
]
if failures:
    sys.exit("FAIL:\n  " + "\n  ".join(failures))
print("PASS: %d request paths route to the intended service" % len(expected))
'
