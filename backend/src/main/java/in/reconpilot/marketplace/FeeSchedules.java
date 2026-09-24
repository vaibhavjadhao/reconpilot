package in.reconpilot.marketplace;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The published rate cards, and which one applied on a given date.
 *
 * <p>Every rate here is traceable to a published source named in the schedule.
 * Rates we could not cite are absent, and asking for one raises
 * {@link UnpublishedRateException}. That is a deliberate choice: this tool's
 * only value is being independently right about money, and a plausible
 * invented rate destroys that more thoroughly than an honest gap.
 */
public final class FeeSchedules {

    private FeeSchedules() {}

    static final BigDecimal GST = new BigDecimal("0.18");
    static final long RUPEE = 100L;

    /**
     * Amazon India, from 16 March 2026.
     *
     * <p>Source: Amazon's March 2026 fee reduction -- zero referral fee on
     * products under Rs 1,000 across more than 1,800 categories, and closing
     * fees cut to Rs 20 below Rs 300 and Rs 26 between Rs 300 and Rs 500.
     */
    static final FeeSchedule AMAZON_2026_03_16 = new FeeSchedule(
            Marketplace.AMAZON,
            LocalDate.of(2026, 3, 16),
            "Amazon.in seller fee update effective 16 March 2026",
            1_000 * RUPEE,
            Set.of(),                    // Amazon's exemption is by price, not category
            amazonRates(),
            amazonClosingFees(),
            GST);

    /**
     * Flipkart, from 18 September 2026.
     *
     * <p>Source: Flipkart's September 2026 rate policy -- 0% commission on any
     * product below Rs 1,000 in any category, and 0% on all fashion products
     * at any price.
     */
    static final FeeSchedule FLIPKART_2026_09_18 = new FeeSchedule(
            Marketplace.FLIPKART,
            LocalDate.of(2026, 9, 18),
            "Flipkart seller rate policy effective 18 September 2026",
            1_000 * RUPEE,
            // "All fashion products at any price" -- the categories we model
            // that fall inside fashion.
            Set.of(ProductCategory.APPAREL,
                   ProductCategory.FOOTWEAR,
                   ProductCategory.FASHION_JEWELLERY),
            flipkartRates(),
            new TreeMap<>(),             // fixed-fee slabs not citable; see below
            GST);

    private static Map<ProductCategory, BigDecimal> amazonRates() {
        // Only the two category rates we can cite. Everything else is absent
        // on purpose -- the published reduction was quoted as "4% to 9.5%
        // lower", which is a change, not a rate, and cannot be turned into one.
        Map<ProductCategory, BigDecimal> m = new EnumMap<>(ProductCategory.class);
        m.put(ProductCategory.MOBILE_PHONES,     new BigDecimal("0.05"));
        m.put(ProductCategory.FASHION_JEWELLERY, new BigDecimal("0.225"));
        return m;
    }

    private static Map<ProductCategory, BigDecimal> flipkartRates() {
        // Flipkart publishes a 3%-25% band by category but not, in any source
        // we have, the per-category figures. What IS citable is the exemption,
        // which is also where the common overcharge happens.
        return new EnumMap<>(ProductCategory.class);
    }

    private static TreeMap<Long, Long> amazonClosingFees() {
        // Key is the INCLUSIVE ceiling of the slab, so a lookup is
        // ceilingEntry(price). Above the last key there is no citable fee.
        TreeMap<Long, Long> slabs = new TreeMap<>();
        slabs.put(300 * RUPEE, 20 * RUPEE);   // up to Rs 300  -> Rs 20
        slabs.put(500 * RUPEE, 26 * RUPEE);   // Rs 300-500    -> Rs 26
        return slabs;
    }

    /** Newest first, so the first match is the one in force. */
    private static final List<FeeSchedule> ALL =
            List.of(FLIPKART_2026_09_18, AMAZON_2026_03_16);

    /**
     * The rate card that applied to an order placed on {@code orderedOn}.
     *
     * <p>Keyed on when the ORDER happened, never on today. Reconciling a
     * six-month-old settlement against the current card is the single easiest
     * way to manufacture thousands of breaks that are not real.
     */
    public static FeeSchedule inForceOn(Marketplace marketplace, LocalDate orderedOn) {
        return ALL.stream()
                .filter(s -> s.marketplace() == marketplace)
                .filter(s -> !orderedOn.isBefore(s.effectiveFrom()))
                .findFirst()
                .orElseThrow(() -> new UnpublishedRateException(
                        "No published %s rate card covers %s. The earliest we hold is later than that date."
                                .formatted(marketplace, orderedOn)));
    }
}
