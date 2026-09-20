#!/usr/bin/env bash
# Checks that the rendered Ingress routes each request path to the intended Service.
#
# The rendered manifest is the chart's generated output, so it is parsed into its
# path/pathType/backend triples and evaluated under nginx's location precedence (exact
# first, then regex in order, then longest prefix) -- which is what ingress-nginx turns
# those paths into. Requires helm and docker (for the YAML-to-JSON conversion).
set -euo pipefail

CHART="$(cd "$(dirname "$0")" && pwd)"

helm template "$CHART" --set tls.crt=dummy --set tls.key=dummy --show-only templates/ingress.yaml --show-only templates/ingress-static.yaml \
  | docker run --rm -i mikefarah/yq -o=json -N ea '[.]' \
  | python3 -c '
import json, re, sys

expected = {
    "/": "frontend",
    "/api/shorten": "frontend",
    "/_next/static/chunks/main-abc123.js": "frontend",
    "/favicon.ico": "frontend",
    "/evil.js": "shortener-service",
    "/dashboard": "frontend",
    "/dashboard/my-link": "frontend",
    "/v1/shorten": "shortener-service",
    "/v1/links/abcd": "shortener-service",
    "/abcd": "resolver-service",
    "/my-link_1": "resolver-service",
}

docs = json.load(sys.stdin)
ingresses = {d["metadata"]["name"]: d for d in docs}

# Static assets must not carry the write-path rate limit: one page load is a burst of chunks.
limited = {
    name: "nginx.ingress.kubernetes.io/limit-rps" in (d["metadata"].get("annotations") or {})
    for name, d in ingresses.items()
}
if limited.get("hopr-static-ingress") is not False or limited.get("hopr-ingress") is not True:
    sys.exit("FAIL: rate limit annotations on the wrong Ingress: %s" % limited)

# ingress-nginx evaluates every Ingress for the host as one merged set of locations.
paths = [
    (p["path"], p["pathType"], p["backend"]["service"]["name"])
    for d in docs
    for rule in d["spec"]["rules"]
    for p in rule["http"]["paths"]
]


def route(request):
    for path, kind, service in paths:
        if kind == "Exact" and path == request:
            return service
    for path, kind, service in paths:
        if kind == "ImplementationSpecific" and re.match(path, request):
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
