package in.reconpilot.mdr;

/**
 * Everything needed to compute MDR for one transaction.
 *
 * @param amountPaise    transaction value in paise (never a floating point type)
 * @param txnType        P2P / P2M / P2PM
 * @param rail           how the payment was made
 * @param payeeCategory  merchant category of the party RECEIVING the money
 */
public record MdrInput(
        long amountPaise,
        TxnType txnType,
        PaymentRail rail,
        PayeeCategory payeeCategory
) {
    public MdrInput {
        if (amountPaise < 0) {
            throw new IllegalArgumentException("amountPaise must not be negative: " + amountPaise);
        }
    }
}
