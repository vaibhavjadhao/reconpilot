package in.reconpilot.marketplace;

/**
 * The separately-billed parts of a marketplace fee.
 *
 * <p>A settlement is not one charge, it is four or five stacked on top of each
 * other, each with its own rule. Keeping them separate is what lets the
 * reconciler say <em>which</em> fee was wrong rather than only that the total
 * was, and a seller cannot dispute "the total looks high".
 */
public enum FeeComponent {
    /** Percentage of item price, by category. */
    COMMISSION,
    /** Flat amount by price slab. Amazon calls it a closing fee, Flipkart a fixed fee. */
    CLOSING_FEE,
    /** By weight and distance. */
    SHIPPING,
    /** Payment handling, and higher for cash on delivery. */
    COLLECTION,
    /** 18% on the fees above -- not on the item price. */
    GST
}
