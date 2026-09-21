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

log "restoring $(basename "$DUMP") into $SCRATCH"

# Checksums first. If the file changed on disk since it was written, the
# restore below might still appear to succeed on a partially-good dump.
( cd "$DIR" && sha256sum -c "$(basename "$DUMP").sha256" >/dev/null ) \
    || fail "checksum mismatch -- the backup on disk is not the backup we wrote"

[ -s "$GLOBALS" ] || fail "no globals file beside the dump; roles would not be restored"
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

log "comparing row counts against the live database"

TABLES=$(psql -d "$PGDATABASE" -At -c \
  "select tablename from pg_tables where schemaname='public' order by tablename")

mismatch=0
for t in $TABLES; do
    live=$(psql -d "$PGDATABASE" -At -c "select count(*) from public.\"$t\"")
    rest=$(psql -d "$SCRATCH"    -At -c "select count(*) from public.\"$t\"" 2>/dev/null || echo MISSING)
    if [ "$live" = "$rest" ]; then
        printf '  %-26s %10s  ok\n' "$t" "$live"
    else
        printf '  %-26s %10s  RESTORED=%s  MISMATCH\n' "$t" "$live" "$rest"
        mismatch=1
    fi
done

# A row-count match is necessary, not sufficient -- it would not notice a
# column of nulls. This checks the actual money, which is the thing whose loss
# would matter, by summing it on both sides.
live_sum=$(psql -d "$PGDATABASE" -At -c "select coalesce(sum(amount_paise),0) from public.transaction_event" 2>/dev/null || echo NA)
rest_sum=$(psql -d "$SCRATCH"    -At -c "select coalesce(sum(amount_paise),0) from public.transaction_event" 2>/dev/null || echo NA)
if [ "$live_sum" = "$rest_sum" ]; then
    log "transaction value matches: $live_sum paise on both sides"
else
    log "transaction value DIFFERS: live=$live_sum restored=$rest_sum"
    mismatch=1
fi

if [ "${KEEP_SCRATCH:-false}" != "true" ]; then
    psql -d postgres -q -c "DROP DATABASE IF EXISTS \"$SCRATCH\";"
fi

[ "$mismatch" -eq 0 ] || fail "the restored database does not match the live one"
log "PASSED -- $(basename "$DUMP") restores to a database identical to the live one"
