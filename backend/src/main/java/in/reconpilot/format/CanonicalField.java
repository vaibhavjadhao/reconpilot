package in.reconpilot.format;

/**
 * The fields ReconPilot needs from any settlement file, whatever the source
 * calls them.
 *
 * <p>This enum is the contract the model maps onto. It is deliberately small:
 * the fewer things the model has to get right, the less there is to verify.
 */
public enum CanonicalField {
    EXTERNAL_TXN_ID("the PSP's own reference for the transaction (RRN, UTR, txn id)"),
    MERCHANT_REF("identifies the merchant receiving the money (VPA, merchant id)"),
    AMOUNT("the transaction value paid to the merchant"),
    TXN_TYPE("P2P, P2M or P2PM classification"),
    RAIL("how the payment was made: QR, intent, autopay, credit line"),
    PAYEE_CATEGORY("merchant category: standard, capital markets, industry programme, education"),
    CHARGED_MDR("the fee the PSP says it deducted"),
    OCCURRED_AT("when the transaction took place");

    private final String description;

    CanonicalField(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
