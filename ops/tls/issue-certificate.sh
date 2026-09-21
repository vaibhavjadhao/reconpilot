#!/bin/sh
# Issues the first Let's Encrypt certificate and switches nginx onto it.
# Run from the project root ON THE SERVER, not on a laptop.
#
#   ./ops/tls/issue-certificate.sh reconpilot.duckdns.org you@example.com
#
# Before running, check all three of these or the challenge will fail:
#   1. The domain's A record points at this server's public IP.
#   2. Port 80 is open in the cloud firewall AND in the host firewall.
#      On Oracle Cloud these are two separate things and the second one is the
#      one everybody forgets.
#   3. The stack is up with TLS_MODE=selfsigned, so something answers on :80.
set -eu

DOMAIN="${1:?usage: issue-certificate.sh <domain> <email> [--staging]}"
EMAIL="${2:?usage: issue-certificate.sh <domain> <email> [--staging]}"
STAGING="${3:-}"

COMPOSE="docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml"

say() { echo; echo "==> $*"; }

say "Checking that $DOMAIN reaches this server on port 80"
# Let's Encrypt will do exactly this. Finding out now costs a second; finding
# out from certbot costs a failed attempt against a rate limit that allows
# only 5 failures per hostname per hour.
token="preflight-$(date +%s)"
$COMPOSE exec -T frontend sh -c \
    "mkdir -p /var/www/acme/.well-known/acme-challenge && echo $token > /var/www/acme/.well-known/acme-challenge/$token"
got=$(curl -fsS --max-time 15 "http://$DOMAIN/.well-known/acme-challenge/$token" 2>/dev/null || true)
$COMPOSE exec -T frontend rm -f "/var/www/acme/.well-known/acme-challenge/$token"

if [ "$got" != "$token" ]; then
    echo "FAILED: http://$DOMAIN/.well-known/acme-challenge/$token did not return the token."
    echo "Got: '${got:-<nothing>}'"
    echo
    echo "Check, in this order:"
    echo "  dig +short $DOMAIN          -- does it match this server's public IP?"
    echo "  the cloud firewall / security list  -- is TCP 80 allowed in?"
    echo "  sudo iptables -L INPUT -n   -- Oracle's images block 80 by default"
    exit 1
fi
echo "OK -- Let's Encrypt will be able to reach this server."

say "Requesting the certificate"
# --staging first is strongly recommended while working out DNS or firewall
# problems: the staging CA has generous limits and issues an untrusted
# certificate, so a mistake costs nothing. Production allows 5 failed
# validations per hostname per hour and 50 certificates per domain per week.
$COMPOSE run --rm --entrypoint certbot certbot certonly \
    --webroot --webroot-path /var/www/acme \
    -d "$DOMAIN" \
    --email "$EMAIL" \
    --agree-tos --no-eff-email --non-interactive \
    $STAGING

say "Copying the certificate to where nginx reads it"
$COMPOSE run --rm --entrypoint sh certbot -c \
    "cp -L /etc/letsencrypt/live/$DOMAIN/fullchain.pem /etc/nginx/tls/fullchain.pem &&
     cp -L /etc/letsencrypt/live/$DOMAIN/privkey.pem   /etc/nginx/tls/privkey.pem &&
     chmod 600 /etc/nginx/tls/privkey.pem"
# cp -L, not cp: /etc/letsencrypt/live/... are symlinks into ../../archive/,
# and copying the symlink itself would give nginx a dangling path.

say "Restarting nginx in provided mode -- HSTS turns on here"
echo "Set TLS_MODE=provided in .env, then:"
echo "  $COMPOSE up -d frontend"
echo
echo "Then confirm it worked:"
echo "  curl -sI https://$DOMAIN | head -3"
echo "  curl -sI https://$DOMAIN | grep -i strict-transport-security"
