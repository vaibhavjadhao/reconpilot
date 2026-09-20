package in.reconpilot.mdr;

/** How the payment was made. The rail can exempt a transaction outright. */
public enum PaymentRail {
    UPI_QR,
    UPI_INTENT,
    /** Mandates and AutoPay. Exempt (FAQ Q22). */
    UPI_AUTOPAY,
    /** RuPay credit card on UPI, pre-sanctioned credit lines.
     *  Governed by card rules, not this framework (FAQ Q36). */
    UPI_CREDIT_LINE
}
