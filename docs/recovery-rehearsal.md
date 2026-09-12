# Recovery rehearsal

A backup nobody has restored is a guess. This is the record of actually doing
it, of what it cost, and of what happens when the backup is no good.

Scripts: `scripts/backup.sh`, `scripts/restore.sh`.

## What was rehearsed

Against the running Compose stack:

1. Opened an account and posted three deposits of 10.00. Balance 30.00.
2. Took a backup: `pg_dump --format=custom`, 29 KB.
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

**Recovery took 12 seconds** end to end. That figure is mostly the application
starting, and it splits in a way that matters:

| | |
| --- | --- |
| Check the dump, restore it to a staging database, verify it | 1.7 s |
| Stop the application, swap the databases by rename | 1.1 s |
| Application starting and serving again | 11.3 s |

**The service was unavailable for about 12 seconds and the database itself for
about one.** Only the middle row is an outage: the dump is checked and restored
while the application is still serving, and the swap is two metadata renames. At
this data volume the restore is trivial either way; what the split buys is that
a larger dump lengthens the first row rather than the second.

## The restored database is sound, not merely present

A restore that leaves a subtly broken ledger is worse than none, so the same
checks the service runs on itself were run afterwards:

- **Reconciliation is clean.** Every account's balance still matches the ledger
  entries behind it: 2 accounts checked, 0 differences. The restore did not tear
  a transaction in half.
- **The ledger still accepts money.** A new deposit posted and the balance
  moved.
- **Idempotency still holds for what survived.** Re-posting `pre-backup-1`
  returned the original transaction, `balanceAfter` 10.00, and did not post a
  second time.
- **A lost reference id can be re-posted.** `post-backup-4`, which the restore
  erased, was accepted as new work rather than deduplicated against a record
  that no longer exists.

That last point is the recovery path and the hazard in one. It is what lets a
client replay lost work after a restore, and it is only safe because the client
kept its own record of what it sent.

`restore.sh` now runs the balance check itself, against the staging copy, before
that copy becomes the database being served. The run above reported
`restored data checks out` at the 1.65 second mark, which is the point at which
the restore became worth completing.

## A bad backup is refused before anything is destroyed

The first version of these scripts had the order wrong. `restore.sh` checked
that the dump file existed, dropped the database, and only then ran
`pg_restore` — so a dump that could not be read was discovered after the only
other copy of the data was gone. `backup.sh` matched it: a dump that failed
partway left a truncated file in `backups/` looking exactly like a good one.

Both were rehearsed against a throwaway stack. Each of these is now refused with
the live database untouched:

| Dump | Refused with |
| --- | --- |
| Empty file | `refusing to restore an empty file` |
| Not a PostgreSQL archive | `does not appear to be a valid archive` |
| Truncated archive | `could not read from input file: end of file` |
| Readable, but not this schema | `dump has no data for table 'accounts'` |
| Balances not matching their entries | `restored ledger does not reconcile` |

The last one is the one worth having. The dump was a valid archive of the right
schema, restored without error, and was still not fit to serve: it was taken
from a database whose stored balances did not agree with the entries behind
them. It is rejected after being restored to the staging database and before the
swap, so the live database never sees it.

`backup.sh` writes to `<name>.dump.partial`, reads the archive's table of
contents back, checks the ledger tables are in it, and only then renames the
file to its final name. The rename is within one directory, so it is atomic: a
file called `banking_ledger-*.dump` has been read back successfully, and a
failed run leaves nothing. Verified by dumping a database that does not exist,
and one that is not the ledger — both left the output directory empty.

Two things this does **not** protect against: a dump that is internally
consistent but stale, and bit rot after the file was written. Re-verify old
backups by restoring them; that is what `restore.sh` up to the swap now is,
and stopping after the check costs nothing.

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

**Work committed during the restore is lost too.** The dump is checked and
restored to staging while the application is still serving, and anything posted
in those seconds is on the database that gets renamed aside. This does not widen
the recovery point — that was already "everything since the dump" — but it does
mean the pre-outage phase is not free, and a long one should be run with the
application already stopped.

**The database is unavailable for the swap, and the service for the restart.**
The swap needs every connection closed, because PostgreSQL will not rename a
database anything is attached to. This is a full outage, not a rolling one.

**Reducing the loss window needs something this does not do.** Point-in-time
recovery, which would cut the window from hours to seconds, requires continuous
WAL archiving and a base backup. `pg_dump` cannot provide it at any frequency.
That is the change to make if the loss above is unacceptable, and it is a
deployment concern rather than an application one.

## Scale

Everything above is at a few kilobytes. The largest dump taken so far, of a
database left behind by the capacity runs, gives one more point:

| Transactions | Dump | `backup.sh`, verification included |
| --- | --- | --- |
| 3 | 29 KB | 4.1 s |
| 188,629 | 12 MB | 4.1 s |

Both are dominated by starting `docker compose exec`, so neither says much about
a large ledger. The figures in this document are a floor for the procedure, not
a projection.

## Doing it

```bash
# Back up. Prints the path it wrote, and writes nothing if the dump is no good.
scripts/backup.sh

# Restore. Checks the dump, restores it to a staging database, verifies that,
# and only then stops the application and swaps the two.
scripts/restore.sh backups/banking_ledger-20260912T011935Z.dump
docker compose up -d app

# Then confirm the restored ledger agrees with itself:
curl -s -X POST http://localhost:8080/api/reconciliation/runs \
  -H "Authorization: Bearer $(scripts/dev-token.sh ops-team ledger:admin)"
```

The database that was in use before the restore is renamed to
`banking_ledger_before_restore_<timestamp>` rather than dropped, so a restore
that turns out to be the wrong one is reversible. `restore.sh` prints the
command to reclaim that space once the new state is confirmed good; until then
it is the only remaining copy of what was replaced.

A restore needs room for a second copy of the data while the staging database
exists, and a third while the previous one is kept.

Backups are written to `backups/`, which is not tracked by git.
