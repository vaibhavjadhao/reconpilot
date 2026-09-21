# 16. Backups are verified by restoring them, not by checking the cron job ran

Date: 2026-09-21

## Status

Accepted. Closes the "no Postgres backups" half of defect D16.

## Context

Everything ReconPilot knows lives in one PostgreSQL volume: the append-only
transaction log, the reconciliation breaks, the disputes, the users. The
architecture is built on the idea that the log is the truth and the projections
can be rebuilt from it (ADR 0006). That only holds while the log exists. There
was no copy of it anywhere.

The failure mode of a backup system is not noise, it is silence. Nothing reads
a backup, so nothing notices when it stops being written, when it starts being
written empty, or when it is being written correctly but cannot be restored.
Organisations discover this on the day they need it. "We take nightly backups"
is a claim about a scheduled job; recoverability is a different claim, and only
a restore is evidence for it.

## Decision

A `backup` container runs beside the database, from the **same image**, so
`pg_dump` always matches the server version -- an older `pg_dump` refuses to run
against a newer server, and pinning them together removes the question. It is a
container rather than a host cron job because a cron job on the host is a
second place to configure, a second place to forget, and it stops existing the
day the host is replaced.

Each cycle produces three files and then prunes to `BACKUP_KEEP` copies:

- `reconpilot-<ts>.dump` -- `pg_dump --format=custom`, compressed, with a
  readable table of contents so `pg_restore` can list it or restore one table.
- `reconpilot-<ts>.globals.sql` -- `pg_dumpall --globals-only`. **Roles are
  cluster-wide objects and `pg_dump` does not contain them.** A dump restored
  onto a fresh server comes back with no `reconpilot_app` role, so every GRANT
  and every row-level-security policy that names it fails. This is precisely
  how a backup passes a drill on the original server and fails in the real
  disaster. The file carries role password hashes and is as sensitive as the
  dump beside it.
- `.sha256` -- so a later restore can tell "the file we wrote" from "the file
  that is there now".

Four properties were chosen deliberately:

- **The dump is written under a temporary name and renamed only once verified.**
  Rename within a filesystem is atomic, so a crash, a kill, or a full disk
  halfway through can never leave a half-written file sitting in the directory
  looking like a good backup.
- **Pruning happens after a successful backup, never before.** Deleting the
  oldest copy to make room and then failing to write the new one turns a
  retention policy into data loss.
- **The backup runs as the database owner, not as `reconpilot_app`.** Row-level
  security applies to the app role, so a dump taken as that role would contain
  only the rows of whichever tenant happened to be set on the connection --
  which is to say, none. It would succeed, be the right shape, and be empty.
- **The container reports unhealthy if no backup has succeeded in twice the
  interval.** Silence becomes a signal something can alert on.

### The restore drill

`ops/backup/restore-drill.sh` restores the newest backup into a scratch
database and compares it to the live one, table by table, then sums
`transaction_event.amount_paise` on both sides -- a row count would not notice a
column restored as nulls, and the money is the thing whose loss would matter.
It refuses to run if the target is the live database, verifies the checksum
before trusting the file, and checks the globals file actually creates the
roles. It inspects that file rather than executing it: running it would reset
the live cluster's roles to their passwords at backup time, and a drill must
not be able to change the thing it is testing.

```
docker compose -f docker-compose.prod.yml \
    run --rm --entrypoint /ops/restore-drill.sh backup
```

## Verification

Against the live stack holding 1,000,000 transactions:

```
dump            1,000,000 rows -> 65,540,731 bytes in 5s
restore         complete in 4s
row counts      13 of 13 tables identical
value           4,687,731,154,500 paise on both sides -- exact
```

Three corrupted backups were then planted, and each was rejected:

| Planted fault | Caught by |
|---|---|
| A byte altered on disk after the backup was written | checksum mismatch |
| Dump truncated mid-write, checksum regenerated to match | `pg_restore --exit-on-error` |
| Globals file missing | roles check |

The second is the instructive one. `pg_restore --list` -- the check the backup
script itself runs at write time -- **succeeded** on the truncated file, because
the table of contents sits at the front of a custom-format dump and was intact.
Only a full restore found the missing data. A write-time integrity check proves
the file is structurally sane; it does not prove the file is complete. That is
why the drill exists as a separate thing, and why `--exit-on-error` is passed:
`pg_restore` by default reports errors, carries on, and exits 0 with a database
that is missing things.

## What this does not do

The backups sit in a Docker volume on the same machine as the database they
protect. That survives a container being rebuilt, a bad migration, and an
accidental `DELETE`. It does not survive the machine, the disk, or the cloud
region. Anything holding real customer money copies these to object storage in
a different failure domain and runs the drill on a schedule rather than by
hand. Both remain open under D16.
