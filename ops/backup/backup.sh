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

APP_ROLE="${DB_APP_USER:-reconpilot_app}"

# On a brand-new deployment this container is ready long before the schema is.
# PostgreSQL reports healthy as soon as it accepts connections; the tables and
# the reconpilot_app role do not exist until the application has run Flyway.
# Backing up in that window produces a dump that restores onto a fresh server
# WITHOUT the role every GRANT and every row-level-security policy names -- a
# backup that looks fine and cannot be restored where it matters.
#
# CI caught exactly this on its first run.
wait_for_schema() {
    waited=0
    while true; do
        have=$(psql -At -c \
            "select count(*) from pg_roles where rolname = '$APP_ROLE'" 2>/dev/null || echo 0)
        [ "$have" = "1" ] && break
        if [ "$waited" -eq 0 ]; then
            log "waiting for role $APP_ROLE -- the application has not run its migrations yet"
        fi
        waited=$((waited + 10))
        sleep 10
    done
    [ "$waited" -gt 0 ] && log "role $APP_ROLE exists after ${waited}s; starting backups"
    return 0
}

take_backup() {
    ts=$(date -u '+%Y%m%dT%H%M%SZ')
    tmp_db="$DIR/.inprogress-$ts.dump"
    tmp_gl="$DIR/.inprogress-$ts.globals.sql"
    tmp_mf="$DIR/.inprogress-$ts.manifest"
    out_db="$DIR/reconpilot-$ts.dump"
    out_gl="$DIR/reconpilot-$ts.globals.sql"
    out_mf="$DIR/reconpilot-$ts.manifest"

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

    # And the globals must actually carry the roles, or this dump cannot be
    # restored onto a fresh server. Checked here rather than only at restore
    # time so that a useless backup is never counted as a success.
    for role in "$PGUSER" "$APP_ROLE"; do
        grep -q "CREATE ROLE $role" "$tmp_gl" || {
            log "globals do not create role $role -- refusing to keep this backup"
            return 1
        }
    done

    # What the database held at dump time, measured independently of pg_dump
    # by querying the live database. The restore drill checks the restored
    # copy against THIS, not against the live database -- by the time anyone
    # runs a drill the live database has moved on, and comparing against a
    # moving target reports drift as corruption.
    {
        printf '# reconpilot backup manifest\n'
        printf 'dumped_at\t%s\n' "$ts"
        printf 'database\t%s\n' "$PGDATABASE"
        psql -At -c "select tablename from pg_tables where schemaname='public' order by tablename" |
        while read -r t; do
            [ -z "$t" ] && continue
            n=$(psql -At -c "select count(*) from public.\"$t\"")
            printf 'table\t%s\t%s\n' "$t" "$n"
        done
        v=$(psql -At -c "select coalesce(sum(amount_paise),0) from public.transaction_event" 2>/dev/null || echo NA)
        printf 'value\ttransaction_event.amount_paise\t%s\n' "$v"
    } > "$tmp_mf"

    # Write to a temporary name and rename only once verified. Rename within a
    # filesystem is atomic, so a crash or a full disk halfway through can never
    # leave a half-written file sitting there looking like a good backup.
    mv "$tmp_db" "$out_db"
    mv "$tmp_gl" "$out_gl"
    mv "$tmp_mf" "$out_mf"
    ( cd "$DIR" && sha256sum "$(basename "$out_db")" "$(basename "$out_gl")" \
        "$(basename "$out_mf")" > "$(basename "$out_db").sha256" )

    log "wrote $(basename "$out_db") ($(wc -c < "$out_db") bytes)"
}

prune() {
    # Pruning happens AFTER a successful backup, never before. Deleting the
    # oldest copy to make room and then failing to write the new one is how a
    # retention policy turns into data loss.
    ls -1t "$DIR"/reconpilot-*.dump 2>/dev/null | tail -n "+$((KEEP + 1))" |
    while read -r old; do
        log "pruning $(basename "$old")"
        rm -f "$old" "$old.sha256" "${old%.dump}.globals.sql" "${old%.dump}.manifest"
    done
}

cleanup_stale() {
    # Anything still called .inprogress-* is the wreckage of a previous crash.
    rm -f "$DIR"/.inprogress-* 2>/dev/null || true
}

mkdir -p "$DIR"
cleanup_stale
wait_for_schema

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
