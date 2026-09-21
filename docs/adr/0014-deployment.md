# ADR 0014: Ship as containers, with every secret supplied from outside

**Status:** Accepted
**Date:** 2026-09-21

## Context

Nothing was deployable. No Dockerfiles, passwords written into
`application.properties`, and `localhost` hardcoded in four places. The system
ran only on a machine someone had set up by hand.

## Decisions

**Multi-stage builds.** The build stage carries Maven, a JDK and the whole
dependency cache -- roughly 700 MB of things worth nothing at runtime and
useful to an attacker. Only the jar crosses into the runtime stage, which is a
JRE with no compiler and no build tools.

**Non-root.** A container process running as root is root on the host kernel if
it escapes. Nothing here needs privilege.

**`-XX:MaxRAMPercentage=70`, not a fixed `-Xmx`.** Modern JVMs read the
container's memory limit rather than the host's, so the heap follows the limit
in `docker-compose.prod.yml` with no rebuild. A hardcoded heap either wastes
the difference or gets OOM-killed when the limit is lowered.

**Only the frontend publishes a port.** Postgres, Kafka and Redis are reachable
on the internal network and nowhere else. The development file publishes them
so a laptop can reach them with `psql`; a deployed database that answers the
internet is a database that gets found.

**nginx serves the SPA and proxies `/api`.** The browser sees one origin, so
CORS never arises -- in development the Vite proxy stood in for exactly this.
`try_files $uri /index.html` is what makes deep links and page refreshes work:
without it, every URL except `/` is a 404 the moment someone reloads.

**Every environment-specific value is `${VAR:local-default}`.** The defaults
keep `mvn spring-boot:run` and the test suite working untouched; a container
supplies real values.

## StartupSecretCheck: a hard failure, not a warning

The JWT signing secret is the whole of authentication. Anyone holding it can
mint a token for any user of any tenant, and the development value is committed
in plain text. An instance running it does not have weak authentication, it has
none -- and nothing would look wrong. Logins work, tokens verify, the dashboard
loads.

So the application refuses to start. The check is gated on
`RECONPILOT_REQUIRE_EXTERNAL_SECRETS`, which the image sets and a laptop does
not, so local development stays frictionless and the check applies exactly
where it matters.

**It runs in `@PostConstruct`, not as an `ApplicationRunner`.** The first
version was a runner, and testing it showed `Started BackendApplication`
printed immediately *before* the refusal: Tomcat had already accepted
connections while the compromised secret was live. Failing during context
refresh means the server never binds. A guard that has never been watched
refusing is a hypothesis, not a control.

## Three defects deployment exposed

**The app role's password was unconfigurable.** V6 created it with a literal
`PASSWORD 'localdev_app'`, so a deployment could set `DB_APP_PASSWORD` to
anything and the role would still only accept the value compiled into the
migration. Nothing in the test suite could have caught it, because
Testcontainers connects as the owner. V6 cannot be edited -- Flyway checksums
applied migrations -- so V8 sets the password from a Flyway placeholder.

**`chown -R` doubled the image.** Running it after `COPY` rewrote the 109 MB
jar, and a rewritten file is stored again in a new layer: 721 MB for a JRE and
a jar. `COPY --chown` writes the ownership correctly the first time. 721 MB ->
503 MB, one line.

**`npm ci`, not `npm install`.** `ci` installs exactly what the lockfile says
and fails if `package.json` disagrees, so an image built today and one built in
six months contain the same dependencies.

## Verified

The full pipeline through nginx into the containerised backend: 1,000,000 rows
ingested in 31 seconds, reconciled in 6, **7,300 breaks found against 7,300
planted -- an exact match on every category**, Rs 133,846 recoverable. Deep
links resolve, unauthenticated calls are refused, and the secret guard was
watched refusing.

## Consequences

- `docker compose -f docker-compose.prod.yml up -d --build` is the whole
  deployment. The same images run on a VPS, Railway, Render, Fly or ECS.
- Secrets live in `.env`, which is gitignored; `.env.example` documents each
  one and how to generate it.
- **Single-node only.** One Kafka broker means nothing is replicated, so a
  broker loss is data loss; a real deployment needs three with a replication
  factor of 3. There is no TLS -- that belongs at a load balancer or ingress in
  front of nginx. Postgres has no backup schedule. These are deliberate
  omissions for a single-machine deployment, not oversights, and none should
  survive contact with real customer money.
