package in.reconpilot.marketplace;

import java.time.LocalDate;

/**
 * One order line from a marketplace settlement report.
 *
 * @param marketplace     which marketplace produced the report
 * @param orderedOn       order date -- picks the rate card, so never "today"
 * @param itemPricePaise  price the buyer paid for the item, before any fee
 * @param category        product category, which sets the commission rate
 * @param paymentMode     prepaid or cash on delivery
 * @param zone            distance band the parcel travelled
 * @param channel         who shipped it
 */
public record SettlementInput(
        Marketplace marketplace,
        LocalDate orderedOn,
        long itemPricePaise,
        ProductCategory category,
        PaymentMode paymentMode,
        ShippingZone zone,
        FulfilmentChannel channel
) {
    public SettlementInput {
        if (itemPricePaise < 0) {
            throw new IllegalArgumentException("itemPricePaise must not be negative: " + itemPricePaise);
        }
    }
}
