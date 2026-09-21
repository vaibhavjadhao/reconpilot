#!/bin/sh
# Chooses the server block nginx will run, and makes sure a certificate exists
# before it tries to load one. The stock nginx image runs every executable in
# /docker-entrypoint.d in name order before starting nginx, so this runs first
# and nginx never sees a half-configured state.
#
# TLS_MODE:
#   provided    real certificate, mounted read-only at /etc/nginx/tls. HSTS on.
#   selfsigned  generate one if absent (default). HSTS deliberately OFF.
#   off         plain HTTP on :80, for when a load balancer in front already
#               terminates TLS.
set -eu

TLS_MODE="${TLS_MODE:-selfsigned}"
TLS_SERVER_NAME="${TLS_SERVER_NAME:-localhost}"
SNIPPETS=/etc/nginx/snippets
TLS_DIR=/etc/nginx/tls
SRC=/etc/nginx/reconpilot
CONF=/etc/nginx/conf.d/default.conf

mkdir -p "$SNIPPETS" "$TLS_DIR" /var/www/acme

# Headers that cost nothing and are always correct. HSTS is appended below only
# when it is safe, because it is the one header a browser refuses to forget.
cat > "$SNIPPETS/security-headers.conf" <<'HDR'
add_header X-Content-Type-Options nosniff always;
add_header X-Frame-Options DENY always;
add_header Referrer-Policy strict-origin-when-cross-origin always;
HDR

hsts_on() {
    cat >> "$SNIPPETS/security-headers.conf" <<'HDR'
add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
HDR
}

case "$TLS_MODE" in

  off)
    echo "reconpilot-tls: TLS_MODE=off -- serving plain HTTP on :80."
    echo "reconpilot-tls: this is only safe if something in front terminates TLS."
    cp "$SRC/server-http.conf" "$CONF"
    # An LB in front is still an https:// origin to the browser, so HSTS is
    # correct there -- but only the operator knows that, so it is opt-in.
    if [ "${TLS_HSTS:-off}" = "on" ]; then hsts_on; fi
    ;;

  provided)
    # Deliberately NO fallback to a self-signed certificate. A fallback turns a
    # missing mount into a site that still answers, with a certificate no
    # client trusts -- a security downgrade that looks like a working deploy.
    if [ ! -s "$TLS_DIR/fullchain.pem" ] || [ ! -s "$TLS_DIR/privkey.pem" ]; then
        echo "reconpilot-tls: FATAL -- TLS_MODE=provided but $TLS_DIR/fullchain.pem" >&2
        echo "reconpilot-tls: and/or privkey.pem is missing or empty. Refusing to start." >&2
        exit 1
    fi
    echo "reconpilot-tls: TLS_MODE=provided -- using the mounted certificate."
    cp "$SRC/server-tls.conf" "$CONF"
    if [ "${TLS_HSTS:-on}" = "on" ]; then hsts_on; fi
    ;;

  selfsigned)
    if [ ! -s "$TLS_DIR/fullchain.pem" ] || [ ! -s "$TLS_DIR/privkey.pem" ]; then
        echo "reconpilot-tls: generating a self-signed certificate for $TLS_SERVER_NAME"
        # subjectAltName, not just CN: every current browser ignores the common
        # name entirely and will report ERR_CERT_COMMON_NAME_INVALID without it.
        san="DNS:$TLS_SERVER_NAME,IP:127.0.0.1"
        if [ "$TLS_SERVER_NAME" != "localhost" ]; then
            san="DNS:$TLS_SERVER_NAME,DNS:localhost,IP:127.0.0.1"
        fi
        openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
            -keyout "$TLS_DIR/privkey.pem" \
            -out    "$TLS_DIR/fullchain.pem" \
            -subj   "/CN=$TLS_SERVER_NAME" \
            -addext "subjectAltName=$san" \
            >/dev/null 2>&1
        chmod 600 "$TLS_DIR/privkey.pem"
    fi
    echo "reconpilot-tls: TLS_MODE=selfsigned -- the browser WILL warn, and is right to."
    cp "$SRC/server-tls.conf" "$CONF"
    # HSTS is off here on purpose. A browser that is told max-age=31536000 by a
    # self-signed host remembers it for a year and then refuses to let anyone
    # click through the warning -- for that host AND port, including
    # localhost:8443, across every project you later run there.
    if [ "${TLS_HSTS:-off}" = "on" ]; then hsts_on; fi
    ;;

  *)
    echo "reconpilot-tls: FATAL -- unknown TLS_MODE '$TLS_MODE'" >&2
    exit 1
    ;;
esac

# The stock image ships 10-listen-on-ipv6-by-default.sh, but it checksums the
# default.conf it is about to patch and skips any file it does not recognise --
# so it silently does nothing for ours, and the container would answer on IPv4
# only. Same guard it uses: if the kernel has no IPv6, do not ask nginx to bind
# an address family that does not exist, because that is a hard startup error.
if [ -f /proc/net/if_inet6 ]; then
    sed -i \
        -e 's/^\( *\)listen 80;/\1listen 80;\n\1listen [::]:80;/' \
        -e 's/^\( *\)listen 443 ssl;/\1listen 443 ssl;\n\1listen [::]:443 ssl;/' \
        "$CONF"
fi
