# 15. TLS terminates at the edge container, and the certificate is a mount

Date: 2026-09-21

## Status

Accepted. Closes the "no TLS" half of defect D16.

## Context

Until now nginx served plain HTTP on port 8081. Everything the console sends
crosses the network in clear: the login request with the user's password, the
`Authorization: Bearer` header on every subsequent call, and the settlement
rows themselves. Anyone on the same network segment -- a coffee-shop access
point, a compromised switch, a cloud provider's internal fabric -- can read a
bearer token and then *be* that user until it expires. Our tokens live 120
minutes.

Three questions had to be answered together.

**Where does TLS terminate?** Not everyone runs this the same way. On a laptop
there is nothing in front. On a VPS, this container is the edge. On ECS or
Kubernetes, an ALB or an ingress controller terminates TLS already, and doing
it twice means two certificates to renew and a second place for the cipher
configuration to be wrong.

**Where does the certificate live?** The tempting answer is "in the image, next
to the config" -- it is one `COPY` line. It is also unrecoverable: an image
layer is content-addressed and immutable, it is pushed to a registry, pulled by
every machine that runs it, and cached in every builder that ever touched it.
A private key placed there has been published, and deleting the Dockerfile line
does not unpublish it.

**Should HSTS be on?** `Strict-Transport-Security` tells a browser to refuse
plain HTTP for this host for a year. That is exactly right for a real
certificate and a trap for a self-signed one: the browser applies the policy to
the *host and port*, will not offer the "proceed anyway" link, and remembers it
long after the experiment is over -- including for `localhost:8443`, which is
the address of every other project you will ever run locally.

## Decision

`TLS_MODE` selects one of three configurations at container start:

| `TLS_MODE` | Ports | Certificate | HSTS |
|---|---|---|---|
| `selfsigned` (default) | 80 redirects to 443 | generated once into a volume | **off** |
| `provided` | 80 redirects to 443 | bind-mounted read-only, **required** | on |
| `off` | 80 serves | none | off (opt-in via `TLS_HSTS=on`) |

Consequences of that table which were deliberate:

- **`provided` has no fallback.** A missing certificate mount aborts startup.
  The alternative -- quietly generating a self-signed certificate instead --
  produces a site that still answers, on a certificate nothing trusts, and the
  deploy looks green. A security downgrade must never be the failure mode.
- **Port 80 is not simply closed** in TLS mode. It answers
  `/.well-known/acme-challenge/` over plain HTTP, because that is how Let's
  Encrypt proves domain control. Redirecting that path too means renewal fails
  every sixty days, silently, until the certificate expires.
- **The redirect is 308, not 301.** A 301 lets a client turn a POST into a GET.
  Ingestion is a POST of a 2 GB file; losing the body on a redirect would be a
  confusing failure to debug.
- **The certificate lives in a Docker volume**, so the self-signed one survives
  a restart. Regenerating it every boot changes the fingerprint the browser was
  asked to trust, so the warning returns every time.

The routing itself -- static assets, the SPA fallback, the `/api` proxy -- lives
in one included file, `nginx/app.conf`, which both the HTTP-only and the TLS
server blocks include. Two copies of routing rules drift: one gets a fix, the
other does not, and the bug appears only on whichever scheme you were not
testing.

The backend is told about the proxy via `server.forward-headers-strategy=FRAMEWORK`.
nginx forwards to it over plain HTTP on the Docker network, so without this
Spring believes the user's connection was `http://` and will build `http://`
links and omit the `Secure` flag on a page that was loaded over TLS. This is
only safe because port 8080 is published nowhere: if a client could reach the
backend directly it could forge `X-Forwarded-For` and poison every log line and
rate limit that reads it.

## The nginx trap this exposed

`add_header` is inherited from the enclosing block **only when the current block
declares none of its own**. `location /assets/` sets `Cache-Control`, so it was
silently dropping every server-level security header. The headers were present
on the pages anyone thought to check and missing on the ones they did not. Each
such location therefore includes the generated `security-headers.conf`
explicitly.

## Verification

With `TLS_MODE=selfsigned`, measured against the running stack:

```
http://localhost:8081/claims/abc-123   308 -> https
https://localhost:8443/claims/abc-123  HTTP/2 200 (SPA deep link intact)
https://localhost:8443/api/breaks      401 (unauthenticated, as before)
Strict-Transport-Security              absent            <- correct here
/assets/index-*.js                     nosniff, DENY, Referrer-Policy present
certificate                            CN=localhost, SAN DNS:localhost, IP:127.0.0.1
```

`TLS_MODE=provided` without a certificate exits 1 before nginx starts; with one,
`Strict-Transport-Security: max-age=31536000; includeSubDomains` appears.
`TLS_MODE=off` serves plain HTTP with no redirect.

End to end through nginx: register 201, login returns a 359-character token,
`GET /api/breaks` with that token 200 -- and the same request over plain HTTP
308s instead of answering.

That last line is worth reading carefully. The redirect protects the *next*
request, not the one that triggered it: a client that sent `Authorization:
Bearer ...` to port 80 has already put the token on the wire in clear before
nginx said "use TLS". Only HSTS closes that, and only from the second visit
onward, which is why it is on for real certificates and why the first visit to
any site is unprotected unless the domain is on the browser's preload list.

## What this does not do

It does not encrypt anything *inside* the Docker network: nginx reaches the
backend, and the backend reaches Postgres and Kafka, over plain connections.
That is acceptable while every service is on one host and the network is not
routable. It stops being acceptable the moment these move to separate machines,
and is recorded in D16.
