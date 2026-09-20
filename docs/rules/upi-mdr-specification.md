# UPI MDR calculation specification

**Effective:** 15 October 2026
**Primary source:** [DFS FAQ, 15 September 2026](dfs-mdr-faq-2026-09-15.pdf) (archived in this repo)

Every rule below cites the FAQ question it derives from. Anything not traceable
to the primary source is marked UNSPECIFIED and must not be implemented until
resolved.

## Merchant account category

MDR applicability is determined by **merchant account categorisation**, not by
individual transaction amounts (Q26).

- **P2PM** (micro merchant): receives up to Rs 1,00,000/month via UPI QR.
  Zero MDR on all transactions regardless of amount (Q23, Q24, Q26).
- **P2M**: standard merchant. Normal rules apply.

### Category transition

A merchant transitions P2PM -> P2M only after inward UPI credit exceeds
Rs 1,00,000 per month **for 3 consecutive months** (Q29).

This is hysteresis: a single high month (festival trading, one large order) does
not reclassify the merchant. State depends on history, not only on the current
value.

There is **no mid-month threshold crossing event**. A P2PM merchant who receives
Rs 5,00,000 in a month still pays zero MDR that month.

## Evaluation order

Precedence is part of the contract. Step 4 must precede step 5.

```
1. P2P transfer?               -> Rs 0                      (Q16)
2. Credit-linked UPI?          -> OUT OF SCOPE, card rules  (Q36)
3. UPI Mandate / AutoPay?      -> Rs 0                      (Q22)
4. Merchant category is P2PM?  -> Rs 0                      (Q23, Q26)
5. Amount <= Rs 2,000?         -> Rs 0                      (Q1, Q31, Q35)
6. Otherwise by merchant category:
     Capital markets    -> min(0.02% x amount, Rs 300)      (Q37)
     Industry program   -> Rs 5 flat                        (Q33, Q39-Q41)
     Education          -> UNSPECIFIED                      (Q42)
     Standard P2M       -> min(0.40% x amount, Rs 300)      (Q31, Q32, Q35)
```

## Rate table

| Category | Condition | MDR |
|---|---|---|
| P2P | any | Rs 0 |
| P2PM | any | Rs 0 |
| AutoPay / Mandate | any | Rs 0 |
| Credit-linked UPI | any | out of scope |
| P2M standard | <= Rs 2,000 | Rs 0 |
| P2M standard | > Rs 2,000 | 0.40%, capped Rs 300 |
| Capital markets | > Rs 2,000 | 0.02%, capped Rs 300 |
| Industry program | > Rs 2,000 | Rs 5 flat |
| Education | > Rs 2,000 | UNSPECIFIED |

**Industry program** covers railways, telecom, insurance, fuel and public
utilities (electricity, water, piped gas), "among others" (Q33, Q39, Q40, Q41).
The full list is not enumerated in the FAQ -- see open questions.

The 0.4% cap binds at exactly Rs 75,000 (0.4% of 75,000 = Rs 300), so the
percentage and cap formulations agree at the boundary. The 0.02% cap binds at
Rs 15,00,000.

## Test cases

Rows marked (FAQ) are figures stated verbatim in the primary source and are
therefore citable in a customer dispute.

| # | Scenario | Amount | Expected MDR |
|---|---|---|---|
| 1 | P2P transfer | 50,000 | 0 |
| 2 | P2PM merchant | 5,000 | 0 |
| 3 | P2PM merchant | 5,00,000 | 0 |
| 4 | P2M standard, at threshold | 2,000.00 | 0 (FAQ Q35) |
| 5 | P2M standard | 3,000 | 12 (FAQ Q35) |
| 6 | P2M standard | 50,000 | 200 (FAQ Q35) |
| 7 | P2M standard, at cap | 75,000 | 300 (FAQ Q35) |
| 8 | P2M standard, above cap | 1,00,000 | 300 (FAQ Q32) |
| 9 | Capital markets | 50,000 | 10 |
| 10 | Capital markets, cap binds | 20,00,000 | 300 |
| 11 | Fuel | 3,000 | 5 |
| 12 | Fuel | 1,00,000 | 5 |
| 13 | Fuel, below threshold | 1,500 | 0 |
| 14 | Insurance premium | 50,000 | 5 |
| 15 | AutoPay SIP | 10,000 | 0 |

## Other provisions

- Consumers are never charged (Q15, Q17, Q19).
- Merchants may not pass MDR on to customers (Q34).
- GST registration is not required for P2PM eligibility (Q28).
