#!/usr/bin/env bash
#
# Takes a backup of the ledger database from the running Compose stack.
#
# Custom format (-Fc) rather than plain SQL: it is compressed, and pg_restore
# can read it selectively, which is what makes a partial recovery possible at
# all.
#
# A file is only given its final name once the archive inside it has been read
# back and found complete. A failed dump would otherwise leave an empty or
# truncated file sitting in the backup directory looking exactly like a good
# one, and the moment that matters is the moment nobody is watching.
#
# Usage:
#   scripts/backup.sh [output-directory]
set -euo pipefail

OUT_DIR="${1:-backups}"
DB_NAME="${POSTGRES_DB:-banking_ledger}"
DB_USER="${POSTGRES_USER:-banking}"
SERVICE="${POSTGRES_SERVICE:-postgres}"

# Tables the archive has to contain to be worth keeping. A dump of the wrong
# database, or of one Flyway never migrated, is readable and useless.
REQUIRED_TABLES=(accounts ledger_transactions ledger_entries audit_logs)

mkdir -p "$OUT_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$OUT_DIR/${DB_NAME}-${stamp}.dump"

# Written under a name that says it is not finished, so an interrupted run
# cannot be mistaken for a backup even if the cleanup below never gets to run.
partial="${target}.partial"

cleanup() {
    rm -f "$partial"
}
trap cleanup EXIT

# The dump runs inside a single transaction's snapshot, so it is consistent as
# of the moment it started. Anything committed after that is not in it.
docker compose exec -T "$SERVICE" \
    pg_dump --format=custom --no-owner --username="$DB_USER" "$DB_NAME" \
    > "$partial"

# pg_dump exiting zero says the server produced an archive; it says nothing
# about what reached the disk on this side of the pipe. Reading the table of
# contents back is what checks that.
toc="$(docker compose exec -T "$SERVICE" pg_restore --list < "$partial")" || {
    echo "backup failed: the archive just written is not readable" >&2
    exit 1
}

for table in "${REQUIRED_TABLES[@]}"; do
    if ! grep -qE "TABLE DATA public $table " <<<"$toc"; then
        echo "backup failed: archive has no data for table '$table'" >&2
        exit 1
    fi
done

# Same directory, so the rename is atomic: the final name never exists over a
# half-written file.
mv "$partial" "$target"

echo "$target"
