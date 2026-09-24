# 17. SettleSure verifies marketplace fees through the same engine

Date: 2026-09-24

## Status

Accepted. First half implemented: the rate cards and the fee calculator.

## Context

The README claimed ReconPilot was "one engine, two adapters" and named
SettleSure as the second. That was not true. SettleSure existed as one line of
marketing and one SQL comment -- `MARKETPLACE_SETTLEMENT` in a list of possible
source types -- and no code at all. Overstating one thing devalues the true
things beside it, particularly in a project whose distinguishing feature is
that it documents its own defects.

There is also a strong reason to build it rather than delete the claim.

**MDR Guard is waiting on a regulator.** UPI MDR starts on 15 October 2026, the
rounding rule is unconfirmed (D7) and we do not yet know how merchant category
is identified in real PSP data (open question 12). Until the NPCI circular
lands, the engine cannot be pointed at a real customer file.

**Marketplace fees are published today.** Amazon and Flipkart print their rate
cards, sellers are charged against them every day, and settlement reports are
already sitting in sellers' accounts. SettleSure can reconcile real data now.

The two problems are also the same shape, which is the whole argument for one
engine: an independently computed expected charge, compared against what was
actually taken, with the difference driven to recovery.

## Decision

A `marketplace` package alongside `mdr`, following the same rules.

**No Spring annotations in the calculator.** Same reason as `MdrCalculator`:
the part that decides how much money someone is owed must be testable without a
context, a database or a broker. Sixteen tests run in 74 milliseconds. A rules
engine that is slow to run is a rules engine that stops being run.

**Fees are modelled as separate components**, not one number: commission,
closing fee, shipping, collection, and GST on top. A settlement is four or five
charges stacked, each with its own rule. Keeping them apart is what lets the
tool say *which* fee was wrong -- a seller cannot dispute "the total looks
high".

**Rate cards are effective-dated.** Amazon changed its rates on 16 March 2026
and Flipkart on 18 September 2026. Reconciling a January order against today's
card would disagree on every row and manufacture thousands of overcharges that
are not real. The card is chosen by **order date**, never by today. An order
predating every card we hold raises rather than silently using the nearest one.

This is the same lesson the MDR engine learned about rounding, in a different
costume: an assumption applied to a million rows stops being small.

**The exemption is evaluated before the category rate.** An order below the
threshold is correctly zero even in a category whose rate we cannot cite.
Checking the rate first would refuse to answer questions we actually know the
answer to.

### The part worth stealing: verified versus unverifiable

`FeeBreakdown` carries two sets -- components computed from a published rate,
and components with no citable rate. Shipping and collection are always in the
second set, because their tables depend on weight bands, zones, seller tier and
fulfilment channel, and we have no source for them.

A reconciliation tool that cannot distinguish **"I checked this and it is
right"** from **"I had nothing to check it against"** will eventually report
the second as the first. A seller who acts on that is disputing a charge nobody
can defend, and the first time that happens the tool is finished. So an
unknown rate never becomes a zero, and GST is computed only over components
that were themselves verified -- taxing an unverified fee would make the tax
unverifiable too.

The same instinct appears in `UnpublishedRateException`, which mirrors how the
MDR engine refuses the education category: a plausible invented rate destroys
trust more thoroughly than an honest gap.

## What is deliberately not implemented

Rates we could not cite are absent, not estimated:

- **Per-category commission above the free band.** Amazon's published change
  was quoted as "4% to 9.5% lower", which is a change, not a rate. Only mobile
  phones (5%) and fashion jewellery (22.5%) are encoded.
- **Flipkart's per-category figures.** The published band is 3%-25%; the
  per-category table is not in any source we hold. What *is* citable is the
  exemption -- and that is also where the common overcharge happens, so the
  gap costs less than it looks.
- **Closing fee above Rs 500**, and **all shipping and collection tables.**

These are recorded in OPEN-QUESTIONS.md. Obtaining the live rate cards from a
seller account is the next primary source, exactly as the NPCI circular is for
MDR.

## Verification

Sixteen tests, covering the exemption boundaries (Rs 1,000 is *not* exempt --
the band is strictly below), the slab boundaries (Rs 300 falls in the first
slab, because the slab reads "up to Rs 300"), rounding, effective dating in
both directions, and the rule that GST is 18% of the **fees** and never of the
item price. That last one matters: charged on the item price instead, a Rs
20,000 phone would attract Rs 3,600 rather than Rs 180 -- twenty times too
much, and invisible in a statement ten thousand lines long.

## Still to build

The comparator that turns a breakdown into breaks, a settlement-report parser,
wiring into the existing ingestion pipeline under
`source_type = MARKETPLACE_SETTLEMENT`, and the console showing both kinds of
break side by side.
