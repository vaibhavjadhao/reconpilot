package in.reconpilot.mdr;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the MDR that <em>should</em> have been charged, per the NPCI
 * framework effective 15 October 2026.
 *
 * <p>Implements the evaluation order in
 * {@code docs/rules/upi-mdr-specification.md}. The order is part of the
 * contract: the first matching rule wins, and specificity is deliberately NOT
 * precedence. A P2PM merchant is exempt regardless of amount, so the category
 * check must precede the amount check.
 *
 * <p>Stateless and therefore thread-safe.
 */
public final class MdrCalculator {

    /** Rs 2,000. MDR applies strictly ABOVE this (FAQ Q35 shows Rs 2,000 -> Rs 0). */
    static final long THRESHOLD_PAISE = 200_000L;

    /** Rs 300 per-transaction cap (FAQ Q32). Binds at exactly Rs 75,000 for 0.4%. */
    static final long CAP_PAISE = 30_000L;

    /** Rs 5 flat for industry-program categories (FAQ Q33). */
    static final long INDUSTRY_FLAT_PAISE = 500L;

    static final BigDecimal RATE_STANDARD        = new BigDecimal("0.004");   // 0.40%
    static final BigDecimal RATE_CAPITAL_MARKETS = new BigDecimal("0.0002");  // 0.02%

    /**
     * PROVISIONAL. The primary source does not specify a rounding rule, yet a
     * rule must exist to compute anything at all: 0.4% of Rs 3,333 is
     * Rs 13.332. HALF_UP is the common commercial convention and is used here
     * as a documented placeholder.
     *
     * <p>This is deliberately the single point of change. See open question 5
     * in {@code docs/OPEN-QUESTIONS.md}; it must be confirmed against the NPCI
     * circular before any figure is shown to a customer.
     */
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public MdrResult calculate(MdrInput in) {

        // 1. Person-to-person is always free (FAQ Q16).
        if (in.txnType() == TxnType.P2P) {
            return new MdrResult(0L, "P2P_EXEMPT");
        }

        // 2. Credit-linked UPI follows card rules, not this framework (FAQ Q36).
        if (in.rail() == PaymentRail.UPI_CREDIT_LINE) {
            throw new UnspecifiedRuleException(
                    "Credit-linked UPI is governed by card rules, not the UPI MDR framework (FAQ Q36)");
        }

        // 3. Mandates and AutoPay are exempt (FAQ Q22).
        if (in.rail() == PaymentRail.UPI_AUTOPAY) {
            return new MdrResult(0L, "AUTOPAY_EXEMPT");
        }

        // 4. P2PM merchants are exempt regardless of amount (FAQ Q23, Q26).
        //    This MUST precede the amount check below.
        if (in.txnType() == TxnType.P2PM) {
            return new MdrResult(0L, "P2PM_EXEMPT");
        }

        // 5. At or below Rs 2,000, no MDR (FAQ Q35).
        if (in.amountPaise() <= THRESHOLD_PAISE) {
            return new MdrResult(0L, "BELOW_THRESHOLD");
        }

        // 6. Otherwise by payee category.
        return switch (in.payeeCategory()) {
            case CAPITAL_MARKETS -> new MdrResult(
                    capped(percentageOf(in.amountPaise(), RATE_CAPITAL_MARKETS)),
                    "CAPITAL_MARKETS_0P02_CAPPED");

            case INDUSTRY_PROGRAM -> new MdrResult(
                    INDUSTRY_FLAT_PAISE,
                    "INDUSTRY_PROGRAM_FLAT_5");

            case STANDARD -> new MdrResult(
                    capped(percentageOf(in.amountPaise(), RATE_STANDARD)),
                    "STANDARD_0P4_CAPPED");

            case EDUCATION -> throw new UnspecifiedRuleException(
                    "Education category rate is not specified by the primary source (FAQ Q42)");
        };
    }

    private static long percentageOf(long amountPaise, BigDecimal rate) {
        return BigDecimal.valueOf(amountPaise)
                .multiply(rate)
                .setScale(0, ROUNDING)
                .longValueExact();
    }

    private static long capped(long mdrPaise) {
        return Math.min(mdrPaise, CAP_PAISE);
    }
}
