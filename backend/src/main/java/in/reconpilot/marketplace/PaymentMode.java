package in.reconpilot.marketplace;

/**
 * How the buyer paid.
 *
 * <p>Matters because the collection fee differs: cash on delivery costs the
 * marketplace more to handle, and in India roughly half of all orders still
 * use it, so the difference is not a rounding detail.
 */
public enum PaymentMode {
    PREPAID,
    COD
}
