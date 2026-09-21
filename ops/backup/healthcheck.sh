#!/bin/sh
# Unhealthy if no backup has succeeded within twice the configured interval.
#
# Backups fail silently by nature: nothing is asking for the data, so nothing
# notices when it stops being written. This turns that silence into a container
# an orchestrator and a dashboard can both see.
set -eu
DIR="${BACKUP_DIR:-/backups}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
STAMP="$DIR/.last-success"

[ -f "$STAMP" ] || { echo "no successful backup yet"; exit 1; }

age=$(( $(date -u +%s) - $(stat -c %Y "$STAMP") ))
if [ "$age" -gt $(( INTERVAL * 2 )) ]; then
    echo "last successful backup was ${age}s ago, interval is ${INTERVAL}s"
    exit 1
fi
echo "last successful backup ${age}s ago"
