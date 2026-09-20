# ADR 0005: Represent all money as integer paise

**Status:** Accepted
**Date:** 2026-09-20

## Context

Binary floating point (`float`, `double`, IEEE 754) cannot exactly represent
most decimal fractions, so arithmetic on monetary values accumulates error and
equality comparison becomes unreliable. In a product whose entire function is
comparing two monetary figures and reporting the difference, this is
disqualifying.

A demonstration over 100,000 identical transactions of Rs 3,333 at 0.4%:

```
double : 1333200.0000005632
paise  : 133300000 paise = 1333000.00
exact  : 1333000.00
```

Two distinct defects are visible and must not be conflated:

1. The trailing `0.0000005632` is floating-point error. Small, but it makes
   `==` unsafe on money, and equality comparison is the core operation here.
2. The Rs 200 gap is a **rounding** difference: 0.4% of Rs 3,333 is Rs 13.332,
   and whether that becomes 13.33 or retains the fraction changes the total at
   scale. This is a policy question, not an arithmetic one, and the primary
   source does not yet answer it (see OPEN-QUESTIONS.md).

## Decision

All monetary values are stored and transported as **`BIGINT` paise** --
integers, never floats, never `REAL`, never `DOUBLE PRECISION`.

Where division is unavoidable (percentage calculations), use `BigDecimal` with
an explicitly stated `RoundingMode`. The rounding mode is defined in exactly one
place, versioned with the ruleset, and never left to a language default.

## Consequences

- Arithmetic is exact and equality comparison is safe.
- The rounding decision becomes explicit and singular rather than implicit and
  scattered. Integers do not remove the decision; they force it to be made once
  and applied identically.
- Amounts must be converted at the system boundary (parsing input, rendering
  output). Those two conversion points are where bugs will concentrate, so they
  get concentrated test coverage.
- `BIGINT` holds ~9.2e18 paise, far beyond any plausible transaction value.
