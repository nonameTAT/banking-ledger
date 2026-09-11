#!/usr/bin/env bash
#
# Takes a backup of the ledger database from the running Compose stack.
#
# Custom format (-Fc) rather than plain SQL: it is compressed, and pg_restore
# can read it selectively, which is what makes a partial recovery possible at
# all.
#
# Usage:
#   scripts/backup.sh [output-directory]
set -euo pipefail

OUT_DIR="${1:-backups}"
DB_NAME="${POSTGRES_DB:-banking_ledger}"
DB_USER="${POSTGRES_USER:-banking}"
SERVICE="${POSTGRES_SERVICE:-postgres}"

mkdir -p "$OUT_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$OUT_DIR/${DB_NAME}-${stamp}.dump"

# The dump runs inside a single transaction's snapshot, so it is consistent as
# of the moment it started. Anything committed after that is not in it.
docker compose exec -T "$SERVICE" \
    pg_dump --format=custom --no-owner --username="$DB_USER" "$DB_NAME" \
    > "$target"

echo "$target"
