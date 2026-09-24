package in.reconpilot.marketplace;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes what a marketplace should have charged for one order line.
 *
 * <p><b>No Spring annotations, on purpose.</b> This is the part that decides
 * how much money someone is owed, so it must be testable without starting a
 * context, a database or a broker. The MDR engine is built the same way and
 * its 23 tests run in under 20 milliseconds. A rules engine you are reluctant
 * to run is a rules engine that stops being run.
 *
 * <p><b>Order of evaluation is part of the rule.</b> The exemption is checked
 * before the category rate, because a category with no published rate is still
 * correctly zero when the order is below the exemption threshold. Checking the
 * rate first would raise {@link UnpublishedRateException} for orders whose
 * answer we actually know with certainty.
 */
public final class MarketplaceFeeCalculator {

    /**
     * HALF_UP, matching the MDR engine.
     *
     * <p>PROVISIONAL, and for the same reason it is provisional there: no
     * marketplace publishes its rounding rule. A single paise of disagreement
     * is invisible on one order and produced 8,174 false breaks across a
     * million MDR rows. Recorded as an open question rather than assumed away.
     */
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public FeeBreakdown calculate(SettlementInput in) {
        FeeSchedule card = FeeSchedules.inForceOn(in.marketplace(), in.orderedOn());

        Set<FeeComponent> verified = EnumSet.noneOf(FeeComponent.class);
        Set<FeeComponent> unverifiable = EnumSet.noneOf(FeeComponent.class);

        // Shipping and collection have no citable rate table. Declared here,
        // once, so nothing downstream can mistake "not compared" for "correct".
        unverifiable.add(FeeComponent.SHIPPING);
        unverifiable.add(FeeComponent.COLLECTION);

        // --- 1. Commission ---------------------------------------------------
        long commission;
        String rule;
        if (card.commissionExempt(in.itemPricePaise(), in.category())) {
            commission = 0L;
            rule = in.itemPricePaise() < card.commissionFreeBelowPaise()
                    ? "COMMISSION_EXEMPT_BELOW_THRESHOLD"
                    : "COMMISSION_EXEMPT_CATEGORY";
            verified.add(FeeComponent.COMMISSION);
        } else {
            Map<ProductCategory, BigDecimal> rates = card.commissionRates();
            BigDecimal rate = rates.get(in.category());
            if (rate == null) {
                // We know a commission is due and not how much. Saying zero
                // would invent a refund; saying "unverified" is the truth.
                commission = 0L;
                rule = "COMMISSION_RATE_UNPUBLISHED";
                unverifiable.add(FeeComponent.COMMISSION);
            } else {
                commission = percentageOf(in.itemPricePaise(), rate);
                rule = "COMMISSION_" + in.category();
                verified.add(FeeComponent.COMMISSION);
            }
        }

        // --- 2. Closing fee --------------------------------------------------
        long closingFee = 0L;
        // ceilingEntry finds the first slab whose ceiling is at or above the
        // price, which is what "up to Rs 300" means. An empty table, or a
        // price above the last slab, is a gap we do not fill.
        var slab = card.closingFeeByMaxPricePaise().ceilingEntry(in.itemPricePaise());
        if (slab != null) {
            closingFee = slab.getValue();
            verified.add(FeeComponent.CLOSING_FEE);
        } else {
            unverifiable.add(FeeComponent.CLOSING_FEE);
        }

        // --- 3. GST ----------------------------------------------------------
        // 18% on the FEES, never on the item price -- a point sellers get wrong
        // constantly, and a marketplace charging GST on the item price would be
        // overcharging by a factor of twenty.
        //
        // Computed only over components we verified. Taxing a fee we could not
        // check would make the tax unverifiable too.
        long gst = percentageOf(commission + closingFee, card.gstRate());
        if (verified.contains(FeeComponent.COMMISSION) || verified.contains(FeeComponent.CLOSING_FEE)) {
            verified.add(FeeComponent.GST);
        } else {
            unverifiable.add(FeeComponent.GST);
        }

        return new FeeBreakdown(commission, closingFee, gst, verified, unverifiable, rule);
    }

    /**
     * Percentage of an integer paise amount, rounded once at the end.
     *
     * <p>longValueExact, not longValue: a silent overflow here is a wrong
     * number that looks like a right one.
     */
    private static long percentageOf(long amountPaise, BigDecimal rate) {
        return BigDecimal.valueOf(amountPaise)
                .multiply(rate)
                .setScale(0, ROUNDING)
                .longValueExact();
    }
}
