#!/usr/bin/env bash
#
# Creates demo users in the local Keycloak: two customers, alice and bob, and one
# administrator, ops, who holds the ledger:admin role on the API client.
#
# For development, CI and end-to-end tests only. ops/keycloak/realm-export.json
# deliberately holds no users, so no deployment has these users or their
# passwords. This creates users only; they own no ledger accounts until they
# open one.
#
# It runs kcadm.sh inside the keycloak service, so start the stack first. A
# Keycloak that is still starting is waited for, up to KEYCLOAK_WAIT_SECONDS.
# Running it again leaves existing users as they are.
#
# Usage:
#   scripts/seed-demo-users.sh
#
# Environment:
#   COMPOSE                  Compose command, default "docker compose"; set it to
#                            pass the same -f or -p options the stack uses
#   DEMO_PASSWORD            password for every demo user, default
#                            <username>-dev-password
#   KEYCLOAK_ADMIN_USERNAME  Keycloak bootstrap administrator, default admin
#   KEYCLOAK_ADMIN_PASSWORD  its password, default admin
#   KEYCLOAK_WAIT_SECONDS    how long to wait for the realm, default 180
set -euo pipefail

read -r -a compose <<< "${COMPOSE:-docker compose}"
admin_user="${KEYCLOAK_ADMIN_USERNAME:-admin}"
admin_password="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
wait_seconds="${KEYCLOAK_WAIT_SECONDS:-180}"
realm=banking
api_client=banking-ledger-api

kcadm() {
    "${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" \
        --config /tmp/kcadm.config
}

# True once the realm answers. Keycloak reports itself ready before it has
# imported the realm, so its own readiness is not enough. The image has no
# curl, so the request goes over bash's /dev/tcp.
realm_answers() {
    "${compose[@]}" exec -T keycloak bash -c "
        exec 3<>/dev/tcp/127.0.0.1/8080 &&
        printf 'GET /realms/$realm HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3 &&
        head -n 1 <&3 | grep -q ' 200 '" > /dev/null 2>&1
}

wait_for_realm() {
    local deadline=$((SECONDS + wait_seconds))

    until realm_answers; do
        if [ -z "$("${compose[@]}" ps -q --status running keycloak)" ]; then
            echo "The keycloak service is not running. Start the stack first." >&2
            exit 69
        fi
        if [ "$SECONDS" -ge "$deadline" ]; then
            echo "The $realm realm did not answer within ${wait_seconds}s." \
                "See: ${compose[*]} logs keycloak" >&2
            exit 75
        fi
        sleep 2
    done
}

create_user() {
    local username="$1" first_name="$2" last_name="$3"
    local password="${DEMO_PASSWORD:-$username-dev-password}"
    local existing

    existing="$(kcadm get users -r "$realm" -q username="$username" -q exact=true \
        --fields id --format csv --noquotes)"

    if [ -n "$existing" ]; then
        echo "$username: already exists, left unchanged"
        return
    fi

    # Email and names are filled in because Keycloak's user profile requires
    # them, and would otherwise stop the first sign-in to ask for them.
    kcadm create users -r "$realm" \
        -s username="$username" \
        -s enabled=true \
        -s email="$username@example.com" \
        -s emailVerified=true \
        -s firstName="$first_name" \
        -s lastName="$last_name" > /dev/null
    kcadm set-password -r "$realm" --username "$username" --new-password "$password"
    echo "$username: created"
}

wait_for_realm

# Logged in once only: by now Keycloak is serving, so a failure here means wrong
# credentials, which waiting would not fix.
kcadm config credentials \
    --server http://localhost:8080 \
    --realm master \
    --user "$admin_user" \
    --password "$admin_password" > /dev/null

create_user alice Alice Customer
create_user bob Bob Customer
create_user ops Ops Administrator

kcadm add-roles -r "$realm" \
    --uusername ops \
    --cclientid "$api_client" \
    --rolename ledger:admin

echo
echo "Demo users and their subjects (the sub claim, and accounts.owner_subject):"
kcadm get users -r "$realm" --fields username,id --format csv --noquotes
