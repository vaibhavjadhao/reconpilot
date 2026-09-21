#!/bin/sh
# Takes a backup of the ReconPilot database on a schedule, verifies that what
# it wrote is readable, and keeps the newest BACKUP_KEEP copies.
#
# Run as a long-lived container rather than a host cron job so that the backup
# has the same lifetime, the same network and the same credentials as the
# database it protects. A cron job on the host is a second place to configure,
# a second place to forget, and it stops working the day the host is replaced.
set -eu

DIR="${BACKUP_DIR:-/backups}"
KEEP="${BACKUP_KEEP:-7}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
ONCE="${BACKUP_ONCE:-false}"

: "${PGHOST:?PGHOST is required}"
: "${PGUSER:?PGUSER is required}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${PGDATABASE:?PGDATABASE is required}"

log() { echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') backup: $*"; }

take_backup() {
    ts=$(date -u '+%Y%m%dT%H%M%SZ')
    tmp_db="$DIR/.inprogress-$ts.dump"
    tmp_gl="$DIR/.inprogress-$ts.globals.sql"
    out_db="$DIR/reconpilot-$ts.dump"
    out_gl="$DIR/reconpilot-$ts.globals.sql"

    log "starting dump of $PGDATABASE on $PGHOST"

    # --format=custom, not plain SQL. It is compressed, it can be restored
    # selectively (one table, or schema-only), and pg_restore can read its
    # table of contents -- which is what makes the verification below possible.
    pg_dump --format=custom --compress=9 --file="$tmp_db"

    # Roles are CLUSTER-wide objects. pg_dump does not contain them, so a dump
    # restored onto a brand-new PostgreSQL server comes back with no
    # reconpilot_app role, and every GRANT and every row-level-security policy
    # referring to it fails. This is the classic way a backup "works" in a
    # drill on the same server and fails in the actual disaster.
    # Note this file contains role password hashes, so it is exactly as
    # sensitive as the database dump beside it.
    pg_dumpall --globals-only > "$tmp_gl"

    # A file that exists is not a backup. Reading its table of contents proves
    # the dump is structurally complete -- a truncated file fails here.
    pg_restore --list "$tmp_db" > /dev/null

    # Write to a temporary name and rename only once verified. Rename within a
    # filesystem is atomic, so a crash or a full disk halfway through can never
    # leave a half-written file sitting there looking like a good backup.
    mv "$tmp_db" "$out_db"
    mv "$tmp_gl" "$out_gl"
    ( cd "$DIR" && sha256sum "$(basename "$out_db")" "$(basename "$out_gl")" \
        > "$(basename "$out_db").sha256" )

    log "wrote $(basename "$out_db") ($(wc -c < "$out_db") bytes)"
}

prune() {
    # Pruning happens AFTER a successful backup, never before. Deleting the
    # oldest copy to make room and then failing to write the new one is how a
    # retention policy turns into data loss.
    ls -1t "$DIR"/reconpilot-*.dump 2>/dev/null | tail -n "+$((KEEP + 1))" |
    while read -r old; do
        log "pruning $(basename "$old")"
        rm -f "$old" "$old.sha256" "${old%.dump}.globals.sql"
    done
}

cleanup_stale() {
    # Anything still called .inprogress-* is the wreckage of a previous crash.
    rm -f "$DIR"/.inprogress-* 2>/dev/null || true
}

mkdir -p "$DIR"
cleanup_stale

while true; do
    if take_backup; then
        prune
        # The healthcheck reads this file's timestamp. If backups stop, the
        # container goes unhealthy instead of sitting there looking fine --
        # the failure mode of a backup system is silence.
        date -u '+%Y-%m-%dT%H:%M:%SZ' > "$DIR/.last-success"
    else
        log "FAILED -- leaving previous backups untouched"
        cleanup_stale
    fi

    if [ "$ONCE" = "true" ]; then
        log "BACKUP_ONCE=true, exiting"
        exit 0
    fi

    log "next backup in ${INTERVAL}s"
    sleep "$INTERVAL"
done
