#!/usr/bin/env bash
# Drives the real nginx.conf in a container against stub upstreams and asserts two things:
# the frontend's /api/shorten route is rate limited per client address (one flooding address
# is cut off with 429 while another address is still served), and the routes that existed
# before the frontend was added still reach their own upstreams. It also asserts the TLS
# termination added alongside: plain HTTP is redirected to HTTPS and serves nothing itself.
# Requires docker.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
CONF="$HERE/nginx.conf"
CERTS="$HERE/certs"
NET=hopr-ratelimit-test
SUBNET=192.168.210.0/24
GATEWAY=hopr-ratelimit-gateway
STUBS=(frontend shortener-service resolver-service)

cleanup() {
  docker rm -f "$GATEWAY" "${STUBS[@]/#/hopr-ratelimit-}" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker network create --subnet "$SUBNET" "$NET" >/dev/null

# Each upstream answers with its own name, so a response body identifies which one the
# gateway routed to. Only the backends are stubbed; the gateway config under test is real.
for spec in frontend:3000 shortener-service:8080 resolver-service:8083; do
  name=${spec%%:*}
  port=${spec##*:}
  docker run -d --name "hopr-ratelimit-$name" --network "$NET" --network-alias "$name" \
    --entrypoint sh nginx:latest -c "printf 'events {}\nhttp { server { listen $port; location / { return 200 \"$name\"; } } }' > /etc/nginx/nginx.conf && nginx -g 'daemon off;'" >/dev/null
done

[ -f "$CERTS/dev.crt" ] || "$HERE/generate-dev-cert.sh" >/dev/null

docker run -d --name "$GATEWAY" --network "$NET" \
  -v "$CONF":/etc/nginx/nginx.conf:ro -v "$CERTS":/etc/nginx/certs:ro nginx:latest >/dev/null

for _ in $(seq 1 30); do
  docker run --rm --network "$NET" curlimages/curl:latest -sk -o /dev/null "https://$GATEWAY/" && break
  sleep 1
done

# curl from a fixed source address over HTTPS; prints "<status> <body>". -k because the
# certificate is self-signed -- the same override a developer's browser needs.
call() {
  local ip=$1 path=$2
  shift 2
  docker run --rm --network "$NET" --ip "$ip" curlimages/curl:latest \
    -sk -w ' %{http_code}' "$@" "https://$GATEWAY$path"
}

fail() { echo "FAIL: $1" >&2; exit 1; }

# The zone is rate=5r/s burst=10, so 25 back-to-back requests must exhaust one address.
statuses=$(docker run --rm --network "$NET" --ip 192.168.210.50 curlimages/curl:latest \
  sh -c 'for i in $(seq 1 25); do curl -sk -o /dev/null -w "%{http_code}\n" -X POST https://'"$GATEWAY"'/api/shorten -d "{}"; done')
rejected=$(grep -c 429 <<<"$statuses" || true)
[ "$rejected" -gt 0 ] || fail "flooding address was never rate limited: $(tr '\n' ' ' <<<"$statuses")"

# A different address must still be served: the budget is per address, not site-wide.
other=$(call 192.168.210.51 /api/shorten -X POST -d '{}')
[[ "$other" != *429 ]] || fail "a second address was rate limited by the first address's flood"
[[ "$other" == frontend* ]] || fail "/api/shorten did not reach the frontend: $other"

# The pre-existing routes still win, and the flood did not spend their budget.
for check in "192.168.210.52 /shorten shortener-service -XPOST" "192.168.210.53 /abcd resolver-service" "192.168.210.54 / frontend" "192.168.210.55 /dashboard frontend"; do
  read -r ip path expected extra <<<"$check"
  got=$(call "$ip" "$path" ${extra:+$extra})
  [[ "$got" == "$expected"* ]] || fail "$path reached '$got', expected $expected"
done

# A normal page load is one HTML request plus a burst of static assets from one address.
# None of it may be rejected, while the same address must still be limited on /shorten.
page=192.168.210.56
burst=$(docker run --rm --network "$NET" --ip "$page" curlimages/curl:latest \
  sh -c 'curl -sk -o /dev/null -w "%{http_code}\n" https://'"$GATEWAY"'/; for i in $(seq 1 30); do curl -sk -o /dev/null -w "%{http_code}\n" https://'"$GATEWAY"'/_next/static/chunks/main-$i.js; done; curl -sk -o /dev/null -w "%{http_code}\n" https://'"$GATEWAY"'/favicon.ico')
! grep -q 429 <<<"$burst" || fail "a normal page load was rate limited: $(tr '\n' ' ' <<<"$burst")"

asset=$(call "$page" /favicon.ico)
[[ "$asset" == frontend* ]] || fail "/favicon.ico did not reach the frontend: $asset"

write_statuses=$(docker run --rm --network "$NET" --ip "$page" curlimages/curl:latest \
  sh -c 'for i in $(seq 1 25); do curl -sk -o /dev/null -w "%{http_code}\n" -X POST https://'"$GATEWAY"'/shorten -d "{}"; done')
grep -q 429 <<<"$write_statuses" || fail "/shorten was not rate limited after the page load: $(tr '\n' ' ' <<<"$write_statuses")"

# The exemption covers only what the frontend serves: a crafted asset-looking path that falls
# through to shortener-service must still spend the write-path budget.
crafted=$(docker run --rm --network "$NET" --ip 192.168.210.57 curlimages/curl:latest \
  sh -c 'for i in $(seq 1 25); do curl -sk -o /dev/null -w "%{http_code}\n" https://'"$GATEWAY"'/evil.js; done')
grep -q 429 <<<"$crafted" || fail "a non-frontend .js path dodged the rate limit: $(tr '\n' ' ' <<<"$crafted")"

# --- TLS termination ---------------------------------------------------------------
# Plain HTTP must redirect and must never serve an upstream response itself. Checked on
# a page path and on the write path that carries the API key header.
for path in / /shorten /dashboard /abcd; do
  plain=$(docker run --rm --network "$NET" --ip 192.168.210.58 curlimages/curl:latest \
    -s -o /dev/null -w '%{http_code} %{redirect_url}' "http://$GATEWAY$path")
  [[ "$plain" == 301* ]] || fail "http://$path was not redirected: $plain"
  [[ "$plain" == *"https://$GATEWAY$path" ]] || fail "http://$path redirected somewhere unexpected: $plain"
done

# And the redirect is all port 80 does: no body from any upstream leaks over plaintext.
body=$(docker run --rm --network "$NET" --ip 192.168.210.59 curlimages/curl:latest \
  -s "http://$GATEWAY/")
[[ "$body" != *frontend* && "$body" != *shortener-service* ]] || fail "plain HTTP served an upstream response: $body"

hsts=$(docker run --rm --network "$NET" --ip 192.168.210.60 curlimages/curl:latest \
  -skI "https://$GATEWAY/" | tr -d '\r')
grep -qi '^strict-transport-security: max-age=31536000' <<<"$hsts" || fail "no HSTS header on the HTTPS response"

echo "plain HTTP 301s to HTTPS on every route and serves no upstream body; HSTS present"
echo "rate limited $rejected of 25 requests from one address; other addresses and all routes still served"
echo "a 32-request page load from one address was not rate limited; /shorten and /evil.js from one address still were"
echo "PASS"
