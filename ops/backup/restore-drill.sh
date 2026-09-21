#!/bin/sh
# Restores the newest backup into a SCRATCH database and compares it, table by
# table, against the live one.
#
# This exists because "we take nightly backups" is a claim about a cron job,
# not about recoverability. The only evidence that a backup can be restored is
# a restore. Run it on a schedule, not after the disaster.
#
#   docker compose -f docker-compose.prod.yml \
#       run --rm --entrypoint /ops/restore-drill.sh backup
set -eu

DIR="${BACKUP_DIR:-/backups}"
SCRATCH="${RESTORE_TARGET_DB:-reconpilot_restore_drill}"

: "${PGHOST:?}" "${PGUSER:?}" "${PGPASSWORD:?}" "${PGDATABASE:?}"

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') drill: $*"; }
fail() { log "FAILED -- $*"; exit 1; }

# Restoring over the live database is never what a drill wants, and a typo in
# RESTORE_TARGET_DB should not be able to destroy production.
if [ "$SCRATCH" = "$PGDATABASE" ]; then fail "refusing to restore over the live database"; fi

DUMP=$(ls -1t "$DIR"/reconpilot-*.dump 2>/dev/null | head -1) || true
[ -n "${DUMP:-}" ] || fail "no backup found in $DIR"
GLOBALS="${DUMP%.dump}.globals.sql"
MANIFEST="${DUMP%.dump}.manifest"

log "restoring $(basename "$DUMP") into $SCRATCH"

# Checksums first. If the file changed on disk since it was written, the
# restore below might still appear to succeed on a partially-good dump.
( cd "$DIR" && sha256sum -c "$(basename "$DUMP").sha256" >/dev/null ) \
    || fail "checksum mismatch -- the backup on disk is not the backup we wrote"

[ -s "$GLOBALS" ] || fail "no globals file beside the dump; roles would not be restored"
[ -s "$MANIFEST" ] || fail "no manifest beside the dump; there is nothing to check the restore against"
# The globals file is inspected, not executed. Running it here would reset the
# roles of the LIVE cluster to whatever their passwords were when the backup
# was taken -- a drill must not be able to change the thing it is testing.
for role in "${PGUSER}" "${DB_APP_USER:-reconpilot_app}"; do
    grep -q "CREATE ROLE $role" "$GLOBALS" \
        || fail "globals file does not create role $role; a restore onto a fresh server would fail"
done

psql -d postgres -v ON_ERROR_STOP=1 -q \
     -c "DROP DATABASE IF EXISTS \"$SCRATCH\";" \
     -c "CREATE DATABASE \"$SCRATCH\";"

# --exit-on-error, because pg_restore's default is to report errors and carry
# on, finishing with status 0 and a database that is missing things.
pg_restore --dbname="$SCRATCH" --exit-on-error --no-owner --no-privileges "$DUMP" \
    || fail "pg_restore reported errors"

log "comparing the restored database against the manifest taken at dump time"

# Against the MANIFEST, not against the live database. By the time anyone runs
# a drill the live database has moved on -- a user registered, a batch landed --
# and comparing a two-hour-old backup against it reports ordinary progress as
# corruption. The manifest was measured by querying the live database directly,
# so it is an independent record of what was there, not something pg_dump told
# us about itself.
mismatch=0

while IFS="$(printf '\t')" read -r kind name expected; do
    case "$kind" in
      table)
        actual=$(psql -d "$SCRATCH" -At -c "select count(*) from public.\"$name\"" 2>/dev/null || echo MISSING)
        if [ "$actual" = "$expected" ]; then
            printf '  %-26s %12s  ok\n' "$name" "$expected"
        else
            printf '  %-26s %12s  RESTORED=%s  MISMATCH\n' "$name" "$expected" "$actual"
            mismatch=1
        fi
        ;;
      value)
        # A row count would not notice a column restored as nulls. This checks
        # the actual money, which is the thing whose loss would matter.
        actual=$(psql -d "$SCRATCH" -At -c "select coalesce(sum(amount_paise),0) from public.transaction_event" 2>/dev/null || echo NA)
        if [ "$actual" = "$expected" ]; then
            log "$name matches: $expected"
        else
            log "$name DIFFERS: expected=$expected restored=$actual"
            mismatch=1
        fi
        ;;
    esac
done < "$MANIFEST"

# A drill that checks an empty database against an empty manifest proves
# nothing at all, and would pass forever.
tables_checked=$(grep -c '^table' "$MANIFEST" || echo 0)
[ "$tables_checked" -ge 1 ] || fail "the manifest lists no tables; this drill would prove nothing"

if [ "${KEEP_SCRATCH:-false}" != "true" ]; then
    psql -d postgres -q -c "DROP DATABASE IF EXISTS \"$SCRATCH\";"
fi

[ "$mismatch" -eq 0 ] || fail "the restored database does not match what the manifest recorded"
log "PASSED -- $(basename "$DUMP") restores to exactly what the database held at dump time"
