package in.reconpilot.mdr;

/**
 * Merchant category of the PAYEE. MDR is levied on the merchant receiving the
 * payment, so the payer's category is irrelevant (see ADR 0006).
 */
public enum PayeeCategory {
    /** 0.4% capped at Rs 300 (FAQ Q31, Q32, Q35). */
    STANDARD,
    /** Mutual funds, securities, brokers: 0.02% capped at Rs 300 (FAQ Q37). */
    CAPITAL_MARKETS,
    /** Railways, telecom, insurance, fuel, utilities: flat Rs 5 (FAQ Q33, Q39-41). */
    INDUSTRY_PROGRAM,
    /** FAQ Q42 states only "flat-fee structures or capped processing rates".
     *  No rate is given, so this cannot be computed. */
    EDUCATION
}
