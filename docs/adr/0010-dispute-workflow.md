# ADR 0010: Model the claim lifecycle as an explicit state machine

**Status:** Accepted
**Date:** 2026-09-20

## Context

A break is a finding. A claim is an assertion made to a PSP about that finding,
and it has a life: drafted, filed, acknowledged, accepted or rejected, settled.

The tempting implementation is a mutable `status` column updated wherever
convenient. That is how records end up RECOVERED without ever having been
FILED, and how "who filed this, and why was it rejected?" becomes unanswerable
six months later when money is in dispute.

## Decision

**The legal transitions live in `DisputeStatus` as data**, not as conditionals
spread through a service. The whole lifecycle is readable in one place; adding a
state forces its transitions to be declared; and an illegal transition is
impossible by construction rather than merely unlikely.

**One gate.** Every state change goes through `DisputeService.transition`. There
is deliberately no `/file`, `/accept`, `/reject` endpoint, because separate
endpoints tempt each to do its own checking and the checks drift apart.

**An append-only `dispute_event` table** records every change with its actor,
note and timestamp. The dispute row holds the current state for query
convenience; this holds how it got there. Same reasoning as the transaction
event log.

**Guards encode business rules, not just technical ones:**

- *An undercharge cannot be claimed.* A negative delta means the PSP took too
  little. There is nothing to recover, and asking for money we are not owed is
  simply wrong. The engine reports these for completeness and must never claim
  them.
- *One claim per break*, enforced by a unique index. A duplicate is not a second
  claim; PSPs reject duplicates, and rejected duplicates damage credibility on
  the claims that are real.
- *Recovery cannot exceed the amount claimed.* Partial settlement is normal;
  settling cannot create money.
- *Filing is disabled by default.* See below.

**`claimed_paise` is recorded at creation and never recalculated.** A claim is an
assertion made at a moment in time; if the ruleset changes later, what we filed
does not retroactively change.

## Filing is off by default, because of D7

`reconpilot.claims.filing-enabled` defaults to **false**. Drafting, reviewing,
withdrawing and settling all work; only the step that sends something outward is
gated.

This is defect D7 expressed as a control. The MDR rounding rule is unconfirmed
(open question 5), and a one-paise disagreement produced 8,174 false breaks in a
single 1,000,000-row run. Filing those would mean disputing charges that were
correct, which destroys the credibility the product exists to sell.

A measurement from testing therefore became a business safety control, enforced
in code, with the reason in the error message a user sees.

## HTTP semantics

- **409 Conflict** for an illegal transition: the request is well formed but
  conflicts with current state. 400 would wrongly imply it was malformed.
- **422 Unprocessable Entity** for a refused claim: understood, syntactically
  fine, forbidden by a business rule.

## Consequences

- Any claim's full history is reconstructable, including who acted and why.
- Invalid lifecycles cannot be reached through the API at all.
- Claimed and recovered are reported separately, because only the second is
  money. An ACCEPTED claim is a promise, not a payment.
- Transitions are currently driven manually. Real PSP integration would mean a
  webhook moving claims to ACKNOWLEDGED/ACCEPTED/REJECTED, which is the same
  gate called by a different caller.
