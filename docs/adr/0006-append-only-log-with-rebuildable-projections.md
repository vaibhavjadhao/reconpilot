# ADR 0006: Append-only event log with rebuildable projections

**Status:** Accepted (tenant-isolation claim corrected below)
**Date:** 2026-09-20
**Implements:** ADR 0003, ADR 0004

## Context

ADR 0003 committed to event sourcing with bi-temporal modelling; ADR 0004
established that merchant category is a state machine whose history must be
reconstructable. This ADR records how that is expressed in the schema.

Two further requirements surfaced while modelling:

- **Direction matters.** MDR is levied on the merchant *receiving* a payment.
  The payer's account category is irrelevant. Modelling a transaction as
  belonging to "a merchant" without distinguishing payer from payee silently
  loses this, and would produce confidently wrong results for any entity that
  both pays and receives.
- **Which rule fired matters as much as the result.** Several rules can yield
  zero MDR for different reasons with different lifespans. An AutoPay exemption
  survives a merchant's reclassification to P2M; a P2PM exemption does not.
  Storing only the amount makes a correct figure unexplainable.

## Decision

`transaction_event` is append-only: no UPDATE, no DELETE. A restatement from a
source arrives as a new row bearing the same `external_txn_id` and a later
`recorded_at`. The current view is the greatest `recorded_at`; the view as of
time T is the greatest `recorded_at <= T`.

The payee is modelled explicitly as `payee_merchant_id`. Payers are not
modelled beyond an opaque reference.

Every calculation stores `rule_id` and `ruleset_version` alongside the amount.

All other tables (`merchant_month_total`, `mdr_calculation`, and the category
projection) are **derived**: they may be dropped and rebuilt from the event log
at any time, and are treated as cache rather than truth. `merchant_month_total`
carries an `as_of` watermark so a projection built from partial data cannot be
mistaken for one built from complete data.

Idempotency is enforced by database constraints rather than application logic:
`ingestion_batch` is unique on `(tenant_id, content_hash)`, so re-uploading
identical bytes is rejected by Postgres, not by code a developer can forget to
write.

Tenant isolation uses PostgreSQL row-level security in addition to application
filtering, so that a single omitted WHERE clause cannot leak another customer's
data.

## Consequences

- Any historical figure is reproducible and explainable.
- Late-arriving and restated data are handled by append and replay rather than
  in-place correction.
- Storage grows monotonically; at projected volumes (~18 GB/year including
  history) this is immaterial.
- Reads require derivation. Where that proves slow, projections are cached in
  Redis and rebuilt from the log on invalidation.
- Correctness of projections is testable by rebuilding them from scratch and
  comparing -- a property worth an automated test.

## Correction, 2026-09-20

The claim above that row-level security provides defence in depth is **not true
as implemented**. PostgreSQL exempts a table's owner from RLS unless
`FORCE ROW LEVEL SECURITY` is set, and the application connects as the owner, so
all policies are bypassed. Verified: 2,000,000 rows were returned with no
`app.tenant_id` set.

Tracked as D1 in KNOWN-DEFECTS.md. The fix requires forcing RLS, connecting as a
non-owner role, and setting the tenant per transaction from an authenticated
request -- the last of which needs authentication that does not exist yet, so it
is scheduled with that work rather than patched in isolation.
