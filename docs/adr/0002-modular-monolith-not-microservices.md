# ADR 0002: Build a modular monolith, not microservices

**Status:** Accepted
**Date:** 2026-09-20

## Context

Back-of-envelope estimation for an optimistic year one (1,000 merchants,
~1,000 transactions each per month):

- 1,000,000 transactions/month; 12,000,000/year
- Average write rate: ~0.4 writes/second
- Peak during a one-hour batch ingest: ~280 writes/second
- Storage: ~3.6 GB/year raw, ~18 GB/year including full event history

These numbers are small. A single PostgreSQL instance serves this comfortably
for several years with no sharding and no read replicas.

The genuinely hard requirements are correctness, determinism, auditability and
idempotency -- not throughput or latency.

## Decision

Build a **modular monolith**: one deployable Spring Boot application with
strictly enforced internal module boundaries (ingestion, rules, matching,
disputes, tenancy). One PostgreSQL instance. Redis for caching. Kafka used only
to decouple slow batch work from the web request cycle -- not for throughput.

Modules communicate through explicit interfaces so that any one of them could
later be extracted into its own service without a rewrite.

## Consequences

- Vastly simpler to build, test, debug and deploy as a solo developer.
- No distributed-transaction problem, so correctness is far easier to guarantee.
- Avoids the failure modes (network partitions, partial failures, eventual
  consistency) that microservices introduce and that buy us nothing at this
  scale.
- If a single module ever genuinely needs independent scaling, the module
  boundaries make extraction tractable.
- Trade-off accepted: the whole application scales as one unit, and a defect in
  one module can affect the whole process.
