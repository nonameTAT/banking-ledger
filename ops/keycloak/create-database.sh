#!/bin/sh
#
# Creates Keycloak's own database and user in the shared PostgreSQL instance,
# if either is missing.
#
# The postgres image's init scripts would do the same, but only for an empty
# data directory. This runs on every start of the stack instead, so an existing
# ledger volume gets the database too. Keycloak never uses banking_ledger or its
# user.
#
# Connection settings come from the standard libpq variables (PGHOST, PGUSER,
# PGPASSWORD, PGDATABASE), and the new user's password from
# KEYCLOAK_DB_PASSWORD.
set -eu

psql -v ON_ERROR_STOP=1 -v password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'keycloak')
\gexec

SELECT 'CREATE DATABASE keycloak OWNER keycloak'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')
\gexec
SQL
