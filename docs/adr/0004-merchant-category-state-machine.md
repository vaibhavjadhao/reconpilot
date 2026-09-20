# ADR 0004: Model merchant MDR eligibility as a category state machine

**Status:** Accepted
**Date:** 2026-09-20
**Amends:** ADR 0003 (corrects a factual error in its context)

## Context

ADR 0003 was written from secondary reporting, which accurately said that small
merchants receiving up to Rs 1 lakh per month are exempt from MDR. From that we
inferred a per-transaction threshold with a mid-month crossing point, and
treated the ambiguity of that crossing as the central design problem.

Reading the primary source (DFS FAQ, 15 September 2026) showed the mechanism is
different:

- MDR applicability is determined by **merchant account categorisation**, not by
  evaluating a running monthly total per transaction (Q26).
- A merchant is either P2PM (zero MDR on everything, any amount) or P2M
  (standard rules).
- Transition from P2PM to P2M occurs only after inward UPI credit exceeds
  Rs 1 lakh per month **for 3 consecutive months** (Q29).

There is therefore no mid-month crossing event and no retroactivity question of
the kind ADR 0003 anticipated. A P2PM merchant receiving Rs 5 lakh in a single
month still owes zero MDR for that month.

## Decision

Model each merchant's MDR eligibility as an explicit **state machine** with
states `P2PM` and `P2M`, evaluated at month granularity.

The P2PM -> P2M transition requires three consecutive qualifying months. This is
**hysteresis**: state depends on history, not only on the current value, which
prevents a single seasonal spike from reclassifying a merchant.

Category transitions are themselves recorded as events in the log, so the
category in force at any past date is reconstructable. A dispute about a March
transaction requires knowing the merchant's March category, not today's.

The reverse transition (P2M -> P2PM, should a merchant fall back below the
threshold) is **not specified** by the primary source and must not be
implemented until resolved. See OPEN-QUESTIONS.md.

## Consequences

- The event-sourcing decision in ADR 0003 stands, and is strengthened: we now
  need per-merchant, per-month inward totals, a consecutive-qualifying-month
  streak, and the full history of category transitions. All are inherently
  temporal.
- The rules engine must resolve merchant category *before* evaluating
  transaction amount, since a P2PM merchant is exempt regardless of amount.
- Correctness now depends on correctly attributing inward credit to calendar
  months, making the definition of "month" load-bearing.
- Lesson recorded deliberately: the design was built on an accurate summary of
  an inaccurate mechanism. Primary sources are read before, not after, design.
