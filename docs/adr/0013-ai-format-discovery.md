# ADR 0013: Use a language model to map unfamiliar file formats, and nothing else

**Status:** Accepted
**Date:** 2026-09-21

## Context

Onboarding a PSP means reading a file whose columns nobody has seen before:
`txn_ref`, `Transaction ID`, `UTR No.`, `RRN`, `Settlement Reference`. Amounts
may be rupees or paise. Coded values vary -- `QR`, `UPI_QR`, `QR_CODE`, `1`.

The space of possible headers is open-ended, so no amount of pattern matching
closes it. Every new customer otherwise needs an engineer to write a parser.

## Decision: the model proposes, deterministic code decides

The model reads the header row and a handful of example values and returns a
**proposal**: which column means what, whether amounts are rupees or paise, and
any value translations. That is its entire role.

It **never** computes a fee, never decides whether something is a break, and
never produces a number that reaches a customer. Those stay in
`MdrCalculator`, which is pure, deterministic and covered by figures the
regulator published.

This boundary is the whole design. Everything else in ReconPilot exists to be
deterministic, replayable and paisa-exact. A non-deterministic component in the
calculation path would undo all of it. Mapping unknown column names is the one
part of the problem whose input space is genuinely open-ended, which is exactly
where a model earns its place and a regular expression does not.

## The proposal is not trusted until it parses real rows

`MappingValidator` reads actual rows from the file through the proposed mapping
and checks that every value parses: amounts convert to whole paise, timestamps
are ISO-8601, coded values resolve to real enum constants, required fields are
present. Only then is the mapping marked VALIDATED and used.

That inverts the trust relationship. A plausible-looking mapping is worth
nothing on its own; one that has been read through is worth everything. The
validator has no dependency on the model or the network, so it is fully
unit-testable -- and it is tested harder than anything it guards, because it is
the only thing standing between a confident wrong answer and a million
mis-parsed rows.

The unit check gets particular attention. A mapping can be structurally perfect,
with every column correct, and still be wrong by a factor of 100 in every
figure. `AmountUnit.toPaise` uses `BigDecimal` and `longValueExact`, so rupees
misdeclared as paise fail loudly on the first value with a fractional part
rather than silently producing numbers a hundred times too small.

## Cost: once per format, not once per row

The cache key is a SHA-256 of the **normalised header row**, not of the file.
Two months of the same PSP's statement share a fingerprint and therefore a
mapping. A million-row file costs one call the first time and none afterwards.
Rejected proposals are stored too: a REJECTED row records what was tried and
why it failed, and stops the next upload of the same format spending another
call to fail the same way.

## What is sent, and what that means

The header row plus `reconpilot.ai.format-discovery.sample-rows` example rows
(default 5). Those rows are real settlement data leaving the deployment. The
setting exists so the number is a deliberate choice rather than an accident,
and the property is commented to say so. A deployment handling real customer
files should make that decision explicitly.

## Model configuration

`claude-opus-5` with adaptive thinking. Working out that `Amt (Rs.)` is rupees
while `settlement_value` is paise is reasoning about evidence, not pattern
matching, and the whole value of the feature is getting that right.

Structured output binds the response to a record, so the SDK derives the schema
and returns a typed object rather than a string to parse and hope about.

## Consequences

- A new PSP format can be onboarded without an engineer writing a parser.
- The feature is optional. Without `ANTHROPIC_API_KEY` everything else in
  ReconPilot works; discovery returns 503 with a message saying what to set.
- Building this exposed D12: the discovery failure surfaced to the client as
  **401**, indistinguishable from an expired session, because an ERROR dispatch
  re-enters the security filter chain after the context has been cleared. Fixed
  by exempting ERROR and ASYNC dispatches -- security decisions belong on the
  original REQUEST dispatch.
- **The live API call is not covered by an automated test.** Everything around
  it is: the validator has 18 unit tests, the fingerprint and caching are
  covered, and the no-credentials path is verified end to end. Asserting on a
  model's output would be asserting on something non-deterministic; the
  validator is what makes that acceptable.
