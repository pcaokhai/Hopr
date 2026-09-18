#!/usr/bin/env bash
# Mints the self-signed certificate the gateway serves HTTPS with in local development.
#
# DEV ONLY. Nothing trusts this certificate, so every client needs an explicit override
# (`curl -k`, "Advanced -> Proceed" in a browser). A real deployment must serve a
# CA-issued certificate instead -- see "TLS" in README.md.
#
# The output is gitignored: a private key must never be committed, and a fresh clone
# generates its own by running this script.
set -euo pipefail

OUT_DIR="$(cd "$(dirname "$0")" && pwd)/certs"
CRT="$OUT_DIR/dev.crt"
KEY="$OUT_DIR/dev.key"

if [ -f "$CRT" ] && [ -f "$KEY" ] && [ "${FORCE:-}" != "1" ]; then
  echo "certificate already present at $CRT (FORCE=1 to regenerate)"
  exit 0
fi

mkdir -p "$OUT_DIR"
# The SAN list, not the CN, is what clients match on; cover every name the gateway is
# reached by locally. 825 days is the maximum lifetime Chrome accepts for a leaf cert.
(umask 077 && openssl req -x509 -newkey rsa:2048 -nodes -days 825 -sha256 \
  -keyout "$KEY" -out "$CRT" \
  -subj "/CN=hopr.localhost/O=Hopr local development (self-signed, do not trust)" \
  -addext "subjectAltName=DNS:hopr.localhost,DNS:localhost,IP:127.0.0.1" 2>/dev/null)
chmod 644 "$CRT"

echo "wrote self-signed dev certificate: $CRT"
echo "wrote private key (gitignored):    $KEY"
