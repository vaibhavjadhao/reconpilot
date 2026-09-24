package in.reconpilot.marketplace;

/**
 * Who shipped the order.
 *
 * <p>The seller pays different fees depending on whose warehouse and couriers
 * were used, so this changes both shipping and closing fees.
 */
public enum FulfilmentChannel {
    /** Seller ships it themselves. */
    SELF_SHIP,
    /** Marketplace picks up from the seller (Amazon Easy Ship, Flipkart equivalents). */
    MARKETPLACE_SHIPPED,
    /** Stored and shipped from the marketplace's warehouse (FBA / Flipkart Fulfilled). */
    MARKETPLACE_FULFILLED
}
