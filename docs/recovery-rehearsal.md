# Recovery rehearsal

A backup nobody has restored is a guess. This is the record of actually doing
it, and of what it cost.

Scripts: `scripts/backup.sh`, `scripts/restore.sh`.

## What was rehearsed

Against the running Compose stack:

1. Opened an account and posted three deposits of 10.00. Balance 30.00.
2. Took a backup: `pg_dump --format=custom`, 32 KB.
3. Posted two more deposits of 10.00. Balance 50.00, five ledger transactions.
4. Restored the backup over the live database and restarted the application.

## Result

| | Before the failure | After recovery |
| --- | --- | --- |
| Balance | 50.00 | **30.00** |
| Ledger transactions | 5 | **3** |
| Surviving reference ids | all five | `pre-backup-1..3` only |

The two deposits made after the backup were gone. That is the expected outcome,
and stating it precisely is the point of the exercise.

**Recovery took 12 seconds** end to end: stopping the application, dropping and
recreating the database, restoring the dump, and serving requests again. At this
data volume the restore is trivial; this figure is a floor for the procedure,
not a projection for a large ledger.

## The restored database is sound, not merely present

A restore that leaves a subtly broken ledger is worse than none, so the same
checks the service runs on itself were run afterwards:

- **Reconciliation is clean.** Every account's balance still matches the ledger
  entries behind it: 2 accounts checked, 0 differences. The restore did not tear
  a transaction in half.
- **The ledger still accepts money.** A new deposit posted and the balance moved.
- **Idempotency still holds for what survived.** Re-posting `pre-backup-1`
  returned the original outcome and did not post a second time.
- **A lost reference id can be re-posted.** `post-backup-4`, which the restore
  erased, was accepted as new work rather than deduplicated against a record
  that no longer exists.

That last point is the recovery path and the hazard in one. It is what lets a
client replay lost work after a restore, and it is only safe because the client
kept its own record of what it sent.

## Limits this demonstrates

**Recovery point: everything since the last backup is lost.** The dump is a
snapshot taken inside one transaction's view, consistent as of the moment it
started and containing nothing committed afterwards. With hourly backups, an
hour of posted transactions can disappear. Ledger entries are append-only and
the balance is derived from them, so nothing detects this after the fact: the
restored ledger is internally consistent and simply missing work. Reconciliation
passes. No alert fires.

**A client that does not retry loses the money for good.** Callers were told
`COMPLETED` for the two lost deposits. The service cannot know which
transactions it has forgotten, so recovery depends entirely on callers replaying
their own record against their original `referenceId`. Idempotency makes that
replay safe, but it cannot start it.

**The database is unavailable for the whole restore.** `restore.sh` stops the
application first, because PostgreSQL will not drop a database that anything is
still connected to. This is a full outage, not a rolling one.

**Reducing the loss window needs something this does not do.** Point-in-time
recovery, which would cut the window from hours to seconds, requires continuous
WAL archiving and a base backup. `pg_dump` cannot provide it at any frequency.
That is the change to make if the loss above is unacceptable, and it is a
deployment concern rather than an application one.

## Doing it

```bash
# Back up. Prints the path it wrote.
scripts/backup.sh

# Restore. This replaces the current database; the application is stopped first.
scripts/restore.sh backups/banking_ledger-20260911T204353Z.dump
docker compose up -d app

# Then confirm the restored ledger agrees with itself:
curl -s -X POST http://localhost:8080/api/reconciliation/runs \
  -H "Authorization: Bearer $(scripts/dev-token.sh ops-team ledger:admin)"
```

Backups are written to `backups/`, which is not tracked by git.
