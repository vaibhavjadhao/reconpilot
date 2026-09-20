# ADR 0003: Event sourcing and bi-temporal modelling for threshold state

**Status:** Accepted (context partially corrected by ADR 0004)
**Date:** 2026-09-20

## Context

The NPCI MDR framework effective 15 October 2026 exempts small merchants
receiving up to Rs 1,00,000 per month via UPI QR from MDR entirely. Evaluating
that exemption requires a running monthly total per merchant, which appears
trivial but is not:

1. Settlement data arrives out of order, so a naive running total is
   order-dependent and therefore non-deterministic.
2. Late-arriving transactions move the threshold-crossing point backwards in
   time, invalidating fees already computed as correct.
3. Disputes require reconstructing what the system knew at a past moment, not
   what it knows now.
4. Transactions below Rs 2,000 attract no MDR individually but still count
   toward the Rs 1,00,000 threshold, so they cannot simply be filtered out.

Storing a mutable `monthly_total` column is the direct cause of every one of
these failures: it retains no history, no ordering and no explanation.

## Decision

Do not store derived state as mutable values. Store an **immutable, append-only
log of transaction events** and derive monthly totals, threshold crossings and
MDR calculations from that log.

Model every event **bi-temporally**, with two independent timestamps:

- `occurred_at` -- when the transaction actually took place
- `recorded_at` -- when our system first learned of it

Any historical question becomes a replay of all events where
`recorded_at <= T`, which reproduces the exact state the system held at time T.

Corrections are appended as new events. Nothing is ever mutated or deleted.

## Consequences

- Determinism: results depend on the event set, not on arrival order.
- Auditability: any figure can be explained and reproduced months later, which
  is a hard requirement for a product whose entire premise is disputing
  someone else's arithmetic.
- Late-arriving data is handled by replay rather than by in-place correction.
- Idempotency: replaying an already-known event is a no-op by construction.
- Trade-off accepted: more storage (~5x), and reads require derivation. At
  ~18 GB/year this is irrelevant; where derivation proves slow we will cache
  materialised projections in Redis, keyed so they can be invalidated and
  rebuilt from the log at any time.

## Revision, 2026-09-20

Items 1-3 of the Context above describe a mid-month threshold crossing that the
primary source shows does not exist; see ADR 0004. The decision to use event
sourcing with bi-temporal modelling is unchanged and, if anything, better
supported: merchant category transitions, monthly inward totals and the
three-month qualifying streak are all temporal state requiring replay and audit.
