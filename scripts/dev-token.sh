#!/usr/bin/env bash
#
# Mints an HS256 bearer token for local development, signed with the same secret
# the application verifies against (banking.security.dev-jwt-secret).
#
# This exists only so the stack is usable without an identity provider. A
# deployment trusts an external issuer, and no part of this script runs there.
#
# Usage:
#   scripts/dev-token.sh <subject> [scope ...]
#
# Examples:
#   scripts/dev-token.sh alice                     # a customer
#   scripts/dev-token.sh ops-team ledger:admin     # an administrator
set -euo pipefail

SECRET="${BANKING_DEV_JWT_SECRET:-local-development-only-secret-do-not-deploy}"
TTL_SECONDS="${TOKEN_TTL_SECONDS:-3600}"

if [ $# -lt 1 ]; then
    echo "usage: $0 <subject> [scope ...]" >&2
    exit 64
fi

subject="$1"
shift
scope="$*"

# JWTs use base64url without padding.
b64url() {
    openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}

now="$(date +%s)"
header='{"alg":"HS256","typ":"JWT"}'
payload="$(printf '{"sub":"%s","scope":"%s","iat":%s,"exp":%s}' \
    "$subject" "$scope" "$now" "$((now + TTL_SECONDS))")"

signing_input="$(printf '%s' "$header" | b64url).$(printf '%s' "$payload" | b64url)"

signature="$(printf '%s' "$signing_input" \
    | openssl dgst -sha256 -hmac "$SECRET" -binary \
    | b64url)"

printf '%s.%s\n' "$signing_input" "$signature"
