# ReconPilot

A reconciliation platform that independently recomputes what a merchant
*should* have been charged, compares it against what they *were* charged, and
drives the resulting discrepancies to recovery.

One engine, two adapters:

- **MDR Guard** -- verifies UPI MDR against the NPCI framework effective
  15 October 2026.
- **SettleSure** -- verifies marketplace settlements (Amazon, Flipkart, Meesho)
  against published fee schedules.

## Stack

Java 21 - Spring Boot - PostgreSQL - Redis - Kafka - React + TypeScript

## Running it

```bash
cp .env.example .env          # then fill in the secrets it asks for
docker compose -p reconpilot-prod -f docker-compose.prod.yml up -d --build
open https://localhost:8443   # http://localhost:8081 redirects here
```

The default `TLS_MODE=selfsigned` generates its own certificate, so the browser
will warn on first visit and is right to -- nothing vouches for that
certificate. Set `TLS_MODE=provided` and bind-mount a real one for a deployment,
or `TLS_MODE=off` when a load balancer in front already terminates TLS.

The database is backed up on a schedule by the `backup` container. Prove the
backups are restorable rather than assuming it:

```bash
docker compose -p reconpilot-prod -f docker-compose.prod.yml \
    run --rm --entrypoint /ops/restore-drill.sh backup
```

## Documentation

- [Architecture decision records](docs/adr/)
- [Open questions](docs/OPEN-QUESTIONS.md)
