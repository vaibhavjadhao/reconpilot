# Open questions

Questions that must be answered from primary sources (the NPCI circular and the
Department of Financial Services MDR FAQ) before the rules engine can be
considered correct. Each one changes the money.

## The Rs 1,00,000 small-merchant exemption

1. **What defines "per month"?** Calendar month, rolling 30 days, or financial
   month? Each yields a different threshold-crossing date for the same merchant.

2. **What happens at the crossing?** A merchant at Rs 99,000 receives Rs 5,000.
   Does MDR apply to only the Rs 4,000 above the line, the whole Rs 5,000
   transaction, retroactively to the entire month, or only to subsequent
   transactions? Four readings, four different amounts.

3. **Which transactions count toward the threshold?** UPI QR only, or all UPI
   P2M? Current assumption: sub-Rs 2,000 transactions count toward the
   threshold even though they attract no MDR individually. Must be confirmed.

4. **Does the exemption reset?** If a merchant crosses in one month and falls
   back below in the next, do they regain exempt status immediately?

## Rate application

5. **Cap interaction.** 0.4% caps at Rs 300, binding at exactly Rs 75,000. Does
   the cap apply per transaction or per settlement batch?

6. **Capital-markets rate.** How is the 0.02% category identified from
   transaction data -- by MCC, by counterparty, or by an explicit flag?

7. **GST.** Is GST charged on top of MDR, and at what rate? This materially
   changes every computed figure.

## Data access

8. **Source of truth for charged MDR.** Which PSPs expose MDR line items via
   API rather than only in PDF or spreadsheet statements? This determines how
   much of the ingestion layer must be format-normalisation work.
