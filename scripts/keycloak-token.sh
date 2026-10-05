#!/usr/bin/env bash
#
# Signs a user in to the local Keycloak through the web app's client and prints
# their access token, so the API can be called with a real token from a
# terminal.
#
# It runs the Authorization Code flow with PKCE that a browser runs: it opens the
# login page, submits the credentials, takes the code from the redirect and
# exchanges it with its code verifier. Keycloak publishes no host port, so the
# requests come from a curl container sharing the keycloak service's network.
#
# Development and tests only: it handles the user's password, which a real
# client never sees. The users come from scripts/seed-demo-users.sh.
#
# Usage:
#   scripts/keycloak-token.sh <username> [password]
#
# Examples:
#   TOKEN=$(scripts/keycloak-token.sh alice)   # a customer
#   ADMIN=$(scripts/keycloak-token.sh ops)     # an administrator
#
# Environment:
#   COMPOSE          Compose command, default "docker compose"; set it to pass
#                    the same -f or -p options the stack uses
#   DEMO_PASSWORD    password when none is given, default <username>-dev-password
#   BANKING_APP_URL  the web app's URL, as the stack was started with, default
#                    https://app.example.com
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <username> [password]" >&2
    exit 64
fi

read -r -a compose <<< "${COMPOSE:-docker compose}"
username="$1"
password="${2:-${DEMO_PASSWORD:-$username-dev-password}}"
redirect_uri="${BANKING_APP_URL:-https://app.example.com}/"

keycloak="$("${compose[@]}" ps -q keycloak)"
if [ -z "$keycloak" ]; then
    echo "The keycloak service is not running. Start the stack first." >&2
    exit 69
fi

# PKCE: the verifier stays here, Keycloak only ever sees its SHA-256 hash until
# the code is exchanged.
b64url() {
    openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}
verifier="$(openssl rand 48 | b64url)"
challenge="$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | b64url)"

# shellcheck disable=SC2016 # expanded inside the container, not here
flow='
set -eu
realm=http://localhost:8080/realms/banking/protocol/openid-connect
jar=/tmp/cookies
encoded_redirect=$(printf "%s" "$REDIRECT_URI" | sed "s/:/%3A/g; s#/#%2F#g")

page=$(curl -sS -c $jar -b $jar "$realm/auth?client_id=banking-ledger-spa&response_type=code&scope=openid&redirect_uri=$encoded_redirect&state=cli&code_challenge=$CHALLENGE&code_challenge_method=S256")

# The form posts to the public URL; the same path is served here.
action=$(printf "%s" "$page" \
    | sed -n "s/.*id=\"kc-form-login\"[^>]*action=\"\([^\"]*\)\".*/\1/p; s/.*action=\"\([^\"]*\)\"[^>]*id=\"kc-form-login\".*/\1/p" \
    | head -n 1 | sed "s/&amp;/\&/g; s#^https\{0,1\}://[^/]*#http://localhost:8080#")
if [ -z "$action" ]; then
    echo "Keycloak did not return a login page. Is the banking realm imported?" >&2
    exit 1
fi

location=$(curl -sS -c $jar -b $jar -o /dev/null -D - \
    --data-urlencode "username=$USERNAME" \
    --data-urlencode "password=$PASSWORD" \
    "$action" | tr -d "\r" | sed -n "s/^[Ll]ocation: //p")
code=$(printf "%s" "$location" | sed -n "s/.*[?&]code=\([^&]*\).*/\1/p")
if [ -z "$code" ]; then
    echo "Sign-in failed for $USERNAME: wrong password, or the user does not exist." >&2
    exit 1
fi

curl -sS "$realm/token" \
    --data grant_type=authorization_code \
    --data client_id=banking-ledger-spa \
    --data-urlencode "code=$code" \
    --data-urlencode "redirect_uri=$REDIRECT_URI" \
    --data "code_verifier=$VERIFIER" \
    | sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p"
'

docker run --rm \
    --network "container:$keycloak" \
    -e USERNAME="$username" \
    -e PASSWORD="$password" \
    -e REDIRECT_URI="$redirect_uri" \
    -e CHALLENGE="$challenge" \
    -e VERIFIER="$verifier" \
    --entrypoint sh \
    curlimages/curl:8.22.0 -c "$flow"
