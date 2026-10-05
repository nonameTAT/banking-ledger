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
# It runs kcadm.sh inside the running keycloak service, so start the stack
# first. Running it again leaves existing users as they are.
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
set -euo pipefail

read -r -a compose <<< "${COMPOSE:-docker compose}"
admin_user="${KEYCLOAK_ADMIN_USERNAME:-admin}"
admin_password="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
realm=banking
api_client=banking-ledger-api

kcadm() {
    "${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" \
        --config /tmp/kcadm.config
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
