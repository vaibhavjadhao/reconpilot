#!/bin/sh
# Renews the certificate if it is close to expiry, copies it to where nginx
# reads it, and reloads nginx. Certbot only acts inside the last 30 days, so
# running this daily is correct and costs nothing on the other 60.
#
# Install on the server as a daily cron entry:
#   0 3 * * * cd /home/ubuntu/reconpilot && ./ops/tls/renew.sh >> /var/log/reconpilot-renew.log 2>&1
#
# A certificate that expires because nobody noticed the renewal stopped is the
# single most common way a small deployment goes down, and it always happens
# 90 days after everyone stopped thinking about it.
set -eu

DOMAIN="${DOMAIN:?set DOMAIN, or source .env first}"
COMPOSE="docker compose -f docker-compose.prod.yml -f docker-compose.letsencrypt.yml"

$COMPOSE run --rm --entrypoint certbot certbot renew --webroot --webroot-path /var/www/acme

# Unconditional copy. `certbot renew` exits 0 whether or not it renewed
# anything, so rather than parsing its output for "not due", we just copy --
# it is two files, and being wrong in the other direction means serving an
# expired certificate.
$COMPOSE run --rm --entrypoint sh certbot -c \
    "cp -L /etc/letsencrypt/live/$DOMAIN/fullchain.pem /etc/nginx/tls/fullchain.pem &&
     cp -L /etc/letsencrypt/live/$DOMAIN/privkey.pem   /etc/nginx/tls/privkey.pem &&
     chmod 600 /etc/nginx/tls/privkey.pem"

# Reload, not restart: nginx re-reads the certificate and hands connections to
# new workers without dropping the ones in flight.
$COMPOSE exec -T frontend nginx -s reload
echo "$(date -u '+%Y-%m-%dT%H:%M:%SZ') renew: done, nginx reloaded"
