# Open questions

Questions affecting the correctness of the rules engine. Each one changes the
money. Resolved items are retained with their source so the reasoning is not
re-litigated.

Primary source: [DFS MDR FAQ, 15 Sept 2026](rules/dfs-mdr-faq-2026-09-15.pdf)

---

## Resolved

1. **What defines "per month", and what happens at the crossing?**
   RESOLVED (Q26, Q29). Neither question applies as originally framed. MDR
   depends on merchant account *category*, not a per-transaction running total.
   Transition P2PM -> P2M requires exceeding Rs 1 lakh/month for 3 consecutive
   months. There is no mid-month crossing event.

2. **Do sub-Rs 2,000 transactions count toward the threshold?**
   RESOLVED (Q29). The threshold is measured on total inward UPI credit, so all
   receipts count toward it, including those that attract no MDR individually.

3. **Does the Rs 300 cap apply per transaction or per batch?**
   RESOLVED (Q32, Q35). Per transaction.

4. **Is the Rs 2,000 threshold inclusive or exclusive?**
   RESOLVED (Q35). Exclusive. The FAQ's own table shows Rs 2,000 -> Rs 0. MDR
   applies only *above* Rs 2,000.

---

## Open

5. **Rounding rule.** 0.4% of Rs 3,333 is Rs 13.332. Round half up, half even,
   or truncate? The FAQ never says. Immaterial per transaction; across tens of
   thousands of transactions, systematic rounding differences will surface as
   false breaks. For a product whose premise is that our arithmetic is right,
   this must be resolved before launch.

   A second consequence surfaced while implementing the calculator: the
   rounding mode changes *where the Rs 300 cap begins to bind*. The regulation
   reads as though the cap starts at exactly Rs 75,000, since 0.4% of Rs 75,000
   is exactly Rs 300. Under HALF_UP, 0.4% of Rs 74,999 is Rs 299.996 and rounds
   to Rs 300, so the cap binds fractionally earlier. Under truncation it would
   not. The rounding mode is currently pinned provisionally in
   `MdrCalculator.ROUNDING` with tests asserting the resulting boundary, so
   that changing it is a deliberate and visible act.

6. **Is the P2M -> P2PM reverse transition permitted?** If a merchant falls
   below Rs 1 lakh/month after being reclassified, do they return to P2PM, and
   after how many months? Not addressed (Q29 describes only the forward path).

7. **Exactly when does P2M status take effect** after the third qualifying
   month -- immediately, from the following month, or on a bank review cycle?

8. **Is the Rs 1 lakh threshold QR-only or all UPI inward credit?** Q23 and Q24
   say "through UPI QR"; Q29 says "inward credit of UPI payment". These differ
   for a merchant who also receives UPI via payment links or intent flows.

9. **GST on MDR.** Not mentioned anywhere in the FAQ. If GST is charged on top
   of MDR, every computed figure changes.

10. **Education category rate.** Q42 says only "flat-fee structures or capped
    processing rates". No rate is stated. Cannot be implemented.

11. **Full list of industry-program categories.** Q33 names railways, telecom,
    insurance and fuel "among others"; Q41 adds public utilities. The
    enumeration is open-ended and needs the NPCI circular.

12. **How is merchant category identified in transaction data?** By MCC, by an
    explicit flag from the PSP, or by merchant registration? This determines
    whether we can classify transactions at all from the data we receive.

13. **Source of truth for charged MDR.** Which PSPs expose MDR line items via
    API rather than only in PDF or spreadsheet statements? Determines how much
    of ingestion is format-normalisation work.

---

## Where to look next

The DFS FAQ is a policy document. Operational detail (rounding, MCC mapping,
transition timing) will sit in the **NPCI circular** referenced in Q7, issued by
the UPI and Services Steering Committee. That is the next primary source to
obtain.
