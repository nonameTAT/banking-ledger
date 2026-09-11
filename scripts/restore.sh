#!/usr/bin/env bash
#
# Restores a dump over the ledger database in the running Compose stack.
#
# THIS REPLACES THE CURRENT DATABASE. Everything committed after the dump was
# taken is gone; see docs/recovery-rehearsal.md for what that costs.
#
# Usage:
#   scripts/restore.sh <dump-file>
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "usage: $0 <dump-file>" >&2
    exit 64
fi

DUMP="$1"
DB_NAME="${POSTGRES_DB:-banking_ledger}"
DB_USER="${POSTGRES_USER:-banking}"
SERVICE="${POSTGRES_SERVICE:-postgres}"

if [ ! -f "$DUMP" ]; then
    echo "no such dump: $DUMP" >&2
    exit 66
fi

# The application holds pooled connections, and PostgreSQL will not drop a
# database that anything is still connected to. Stopping the application first
# is part of the procedure, not an optimisation.
echo "stopping the application so the database can be replaced..."
docker compose stop app >/dev/null 2>&1 || true

docker compose exec -T "$SERVICE" \
    psql --username="$DB_USER" --dbname=postgres -v ON_ERROR_STOP=1 -q <<SQL
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid();
DROP DATABASE IF EXISTS $DB_NAME;
CREATE DATABASE $DB_NAME OWNER $DB_USER;
SQL

docker compose exec -T "$SERVICE" \
    pg_restore --username="$DB_USER" --dbname="$DB_NAME" --no-owner --exit-on-error \
    < "$DUMP"

echo "restored $DUMP"
echo "start the application again with: docker compose up -d app"
