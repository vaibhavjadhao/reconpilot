#!/bin/sh
# Loads the demo tenant with data, so that someone opening the live site sees
# the product doing its job rather than an empty table.
#
#   ./ops/demo/seed-demo.sh                          # against localhost
#   BASE_URL=https://reconpilot.duckdns.org ./ops/demo/seed-demo.sh
#
# It drives the real HTTP API -- register, upload, reconcile -- rather than
# inserting rows into the database. A demo seeded by INSERT proves the
# database can hold data. This proves the pipeline works, which is the thing
# being demonstrated, and it breaks the moment the pipeline does.
set -eu

BASE_URL="${BASE_URL:-https://localhost:8443}"
EMAIL="${DEMO_EMAIL:-demo@reconpilot.in}"
PASSWORD="${DEMO_PASSWORD:?set DEMO_PASSWORD, e.g. DEMO_PASSWORD=... ./ops/demo/seed-demo.sh}"
ROWS="${DEMO_ROWS:-250000}"
BREAKS="${DEMO_BREAKS:-2000}"
CSV="${TMPDIR:-/tmp}/reconpilot-demo.csv"

# -k because the local stack uses a self-signed certificate. On a real
# deployment the certificate is valid and this changes nothing.
CURL="curl -sk"

say() { echo; echo "==> $*"; }

say "Generating $ROWS rows with $BREAKS planted breaks"
python3 "$(dirname "$0")/../testdata/generate-settlements.py" \
    --rows "$ROWS" --breaks "$BREAKS" --out "$CSV"

say "Registering $EMAIL"
# 409 if it already exists, which is fine -- this script is meant to be
# re-runnable after a redeploy without anyone having to check first.
code=$($CURL -o /dev/null -w '%{http_code}' -X POST "$BASE_URL/api/auth/register" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"tenantName\":\"ReconPilot Demo\"}")
case "$code" in
  201) echo "created" ;;
  409|400) echo "already exists, continuing" ;;
  *) echo "unexpected $code from register"; exit 1 ;;
esac

say "Logging in"
TOK=$($CURL -X POST "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
[ -n "$TOK" ] || { echo "login failed"; exit 1; }

say "Uploading"
RESP=$($CURL -X POST "$BASE_URL/api/ingest" -H "Authorization: Bearer $TOK" -F "file=@$CSV")
echo "$RESP"
BATCH=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["batchId"])')
SEEN=$(echo "$RESP" | python3 -c 'import sys,json;print(json.load(sys.stdin)["alreadySeen"])')

if [ "$SEEN" = "True" ]; then
    # The generator is seeded, so re-running produces byte-identical content
    # and ingestion recognises it. That is the idempotency working, not a
    # failure -- but there is nothing new to parse, so skip the wait.
    echo "identical file already ingested; not re-parsing"
else
    say "Waiting for the rows to be parsed"
    i=0
    while [ "$i" -lt 60 ]; do
        ST=$($CURL -H "Authorization: Bearer $TOK" "$BASE_URL/api/ingest/$BATCH" \
             | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')
        [ "$ST" = "PARSED" ] && break
        [ "$ST" = "FAILED" ] && { echo "ingestion failed"; exit 1; }
        i=$((i + 1)); sleep 2
    done
    [ "$ST" = "PARSED" ] || { echo "still $ST after 120s"; exit 1; }
    echo "parsed"
fi

say "Reconciling"
$CURL -X POST -H "Authorization: Bearer $TOK" "$BASE_URL/api/recon/$BATCH" -o /dev/null \
    -w 'HTTP %{http_code}\n'

i=0
while [ "$i" -lt 60 ]; do
    S=$($CURL -H "Authorization: Bearer $TOK" "$BASE_URL/api/breaks/summary")
    [ "$S" != "[]" ] && break
    i=$((i + 1)); sleep 2
done

say "Demo tenant is loaded"
# Inside single-quoted shell, Python can use double quotes freely. Escaping
# them would put a backslash inside an f-string expression, which is a syntax
# error -- and one that only shows up at the very end of a long seed run.
echo "$S" | python3 -c '
import sys, json
rows = json.load(sys.stdin)
total = sum(r["count"] for r in rows)
rec   = sum(r["recoverablePaise"] for r in rows)
for r in rows:
    name, n, p = r["breakType"], r["count"], r["recoverablePaise"] / 100
    print(f"  {name:<22} {n:>7,}   Rs {p:>14,.2f}")
# No single quotes anywhere in this Python: the whole program is inside a
# single-quoted shell string, so one would end it and leave Python reading a
# bare name.
label = "TOTAL"
print(f"  {label:<22} {total:>7,}   Rs {rec / 100:>14,.2f}")
'
rm -f "$CSV"
echo
echo "Sign in at $BASE_URL with $EMAIL"
