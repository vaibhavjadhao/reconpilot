package in.reconpilot.marketplace;

/** Which marketplace a settlement line came from. */
public enum Marketplace {
    AMAZON,
    FLIPKART,
    /** Charges no commission, but still bills shipping and other components. */
    MEESHO
}
