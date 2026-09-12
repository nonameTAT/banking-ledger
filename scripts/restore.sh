#!/usr/bin/env bash
#
# Restores a dump over the ledger database in the running Compose stack.
#
# The live database is never touched until the dump has been restored into a
# staging database and checked there. Validating a file's existence and then
# dropping the database is the wrong order: the failure mode it allows is a bad
# dump discovered after the only good copy of the data is already gone.
#
# The order here is:
#   1. read the archive's table of contents, without a database involved
#   2. restore it into a staging database, while the application keeps running
#   3. check the staging database reconciles with itself
#   4. stop the application and swap the two by rename
#
# The application is down only for step 4, and the database it was using is
# renamed aside rather than dropped, so a bad restore is still reversible after
# the swap.
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

REQUIRED_TABLES=(accounts ledger_transactions ledger_entries audit_logs)

if [ ! -f "$DUMP" ]; then
    echo "no such dump: $DUMP" >&2
    exit 66
fi

if [ ! -s "$DUMP" ]; then
    echo "refusing to restore an empty file: $DUMP" >&2
    exit 65
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
STAGING_DB="${DB_NAME}_restore_${stamp}"
PREVIOUS_DB="${DB_NAME}_before_restore_${stamp}"

# PostgreSQL identifiers are capped at 63 bytes and silently truncate, which
# for a database name is how two of these become one.
for name in "$STAGING_DB" "$PREVIOUS_DB"; do
    if [ "${#name}" -gt 63 ]; then
        echo "derived database name is too long for PostgreSQL: $name" >&2
        exit 78
    fi
done

# Runs SQL as the ledger user against the named database.
psql_in() {
    local database="$1"
    shift

    docker compose exec -T "$SERVICE" \
        psql --username="$DB_USER" --dbname="$database" \
        -v ON_ERROR_STOP=1 -q "$@"
}

swapped=0

cleanup() {
    if [ "$swapped" -eq 1 ]; then
        return
    fi

    # The staging database only exists to be promoted. If this run did not get
    # that far, it is scratch space and nothing else refers to it.
    docker compose exec -T "$SERVICE" \
        psql --username="$DB_USER" --dbname=postgres -q \
        -c "DROP DATABASE IF EXISTS \"$STAGING_DB\" WITH (FORCE)" \
        >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --------------------------------------------------------- 1. the archive ---
# Checked before anything is created, because a file that is not a readable
# custom-format archive costs nothing to reject here.

echo "checking $DUMP..."

if ! toc="$(docker compose exec -T "$SERVICE" pg_restore --list < "$DUMP" 2>&1)"; then
    echo "not a readable custom-format dump: $DUMP" >&2
    echo "$toc" >&2
    exit 65
fi

for table in "${REQUIRED_TABLES[@]}"; do
    if ! grep -qE "TABLE DATA public $table " <<<"$toc"; then
        echo "dump has no data for table '$table', refusing to restore it" >&2
        exit 65
    fi
done

# ------------------------------------------------- 2. restore to staging ----
# The application is still serving from the live database throughout this, so a
# restore that fails here costs no availability at all. It does need room for a
# second copy of the data.

echo "restoring into staging database $STAGING_DB..."

docker compose exec -T "$SERVICE" \
    psql --username="$DB_USER" --dbname=postgres -v ON_ERROR_STOP=1 -q \
    -c "CREATE DATABASE \"$STAGING_DB\" OWNER \"$DB_USER\""

docker compose exec -T "$SERVICE" \
    pg_restore --username="$DB_USER" --dbname="$STAGING_DB" \
    --no-owner --exit-on-error \
    < "$DUMP"

# -------------------------------------------------- 3. check the staging ----
# A restore that leaves a subtly broken ledger is worse than none. These are
# the same invariants the service checks on itself, run before the restored
# copy becomes the one being served rather than after.

echo "checking the restored data..."

psql_in "$STAGING_DB" <<'SQL'
DO $$
DECLARE
    missing text;
    failed_migrations integer;
    drifted integer;
BEGIN
    SELECT string_agg(expected, ', ')
    INTO missing
    FROM unnest(ARRAY[
        'accounts',
        'ledger_transactions',
        'ledger_entries',
        'audit_logs'
    ]) AS expected
    WHERE to_regclass('public.' || expected) IS NULL;

    IF missing IS NOT NULL THEN
        RAISE EXCEPTION 'restored database is missing tables: %', missing;
    END IF;

    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION
            'restored database has no Flyway history, so its schema version is unknown';
    END IF;

    SELECT count(*) INTO failed_migrations
    FROM flyway_schema_history
    WHERE success IS FALSE;

    IF failed_migrations > 0 THEN
        RAISE EXCEPTION
            'restored database carries % failed migration(s)', failed_migrations;
    END IF;

    -- Every account's stored balance against the entries behind it. The signs
    -- follow the account category, the same rule the posting code applies.
    SELECT count(*) INTO drifted
    FROM (
        SELECT a.id
        FROM accounts a
        LEFT JOIN ledger_entries e ON e.account_id = a.id
        GROUP BY a.id
        HAVING a.balance <> COALESCE(SUM(
            CASE
                WHEN e.entry_type = 'DEBIT' AND a.account_category = 'ASSET'
                    THEN e.amount
                WHEN e.entry_type = 'CREDIT' AND a.account_category = 'ASSET'
                    THEN -e.amount
                WHEN e.entry_type = 'CREDIT' AND a.account_category = 'LIABILITY'
                    THEN e.amount
                WHEN e.entry_type = 'DEBIT' AND a.account_category = 'LIABILITY'
                    THEN -e.amount
            END
        ), 0)
    ) AS drift;

    IF drifted > 0 THEN
        RAISE EXCEPTION
            'restored ledger does not reconcile: % account(s) disagree with their entries',
            drifted;
    END IF;

    RAISE NOTICE 'restored data checks out';
END
$$;
SQL

# ------------------------------------------------------------- 4. the swap ---
# Only now is the live database touched, and it is renamed rather than dropped.
# Renames are metadata changes, so this is the shortest the outage can be.

echo "stopping the application so the database can be replaced..."
docker compose stop app >/dev/null 2>&1 || true

echo "swapping $DB_NAME for the restored copy..."

# ALTER DATABASE ... RENAME refuses to run while anything is connected, and it
# cannot run inside a transaction block, so the two renames are separate
# statements. If the second one fails the recovery is printed below.
if ! docker compose exec -T "$SERVICE" \
    psql --username="$DB_USER" --dbname=postgres -v ON_ERROR_STOP=1 -q <<SQL
DO \$\$
BEGIN
    PERFORM pg_terminate_backend(pid)
    FROM pg_stat_activity
    WHERE datname IN ('$DB_NAME', '$STAGING_DB')
        AND pid <> pg_backend_pid();
END
\$\$;

ALTER DATABASE "$DB_NAME" RENAME TO "$PREVIOUS_DB";
ALTER DATABASE "$STAGING_DB" RENAME TO "$DB_NAME";
SQL
then
    echo >&2
    echo "the swap failed. Check which of these exists and finish it by hand:" >&2
    echo "  ALTER DATABASE \"$PREVIOUS_DB\" RENAME TO \"$DB_NAME\";   -- to undo" >&2
    echo "  ALTER DATABASE \"$STAGING_DB\" RENAME TO \"$DB_NAME\";    -- to complete" >&2
    exit 1
fi

swapped=1

echo
echo "restored $DUMP"
echo "the database in use before the restore is kept as: $PREVIOUS_DB"
echo "start the application again with: docker compose up -d app"
echo
echo "once the restore is confirmed good, reclaim that space with:"
echo "  docker compose exec -T $SERVICE psql -U $DB_USER -d postgres \\"
echo "    -c 'DROP DATABASE \"$PREVIOUS_DB\"'"
