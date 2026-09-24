package in.reconpilot.marketplace;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

/**
 * One marketplace's published rate card, as it stood from a given date.
 *
 * <p><b>Why this is dated.</b> Amazon changed its rates on 16 March 2026 and
 * Flipkart on 18 September 2026. Reconciling a January order against today's
 * card would disagree on every single row -- thousands of "overcharges" that
 * are nothing of the kind. A rate card without an effective date is not a rate
 * card, it is a snapshot that silently becomes wrong.
 *
 * <p>This is the same lesson the MDR engine learned about rounding: an
 * assumption applied to a million rows stops being small.
 *
 * <p><b>What is deliberately absent.</b> Shipping and collection fees are not
 * here. Their published tables depend on weight bands, zones, seller tier and
 * fulfilment channel, and we have no citable source for them. They are left
 * out rather than estimated, and the calculator reports them as unverifiable
 * instead of quietly treating them as correct. A reconciliation tool that
 * cannot tell "I checked this and it is right" from "I could not check this"
 * is worse than useless -- it is confidently wrong.
 *
 * @param marketplace             which marketplace
 * @param effectiveFrom           first date this card applies
 * @param source                  where the rates came from, for the audit trail
 * @param commissionFreeBelowPaise item price strictly below this attracts no commission
 * @param alwaysFreeCategories    categories at 0% regardless of price
 * @param commissionRates         rate by category, for prices at or above the free band
 * @param closingFeeByMaxPricePaise ceiling of each price slab -> flat fee in paise
 * @param gstRate                 tax applied to the fees, not to the item price
 */
public record FeeSchedule(
        Marketplace marketplace,
        LocalDate effectiveFrom,
        String source,
        long commissionFreeBelowPaise,
        Set<ProductCategory> alwaysFreeCategories,
        Map<ProductCategory, BigDecimal> commissionRates,
        NavigableMap<Long, Long> closingFeeByMaxPricePaise,
        BigDecimal gstRate
) {
    public FeeSchedule {
        // Defensive copies: a rate card that another thread can mutate after
        // the fact is a rate card nobody can reason about.
        alwaysFreeCategories = Set.copyOf(alwaysFreeCategories);
        commissionRates = Map.copyOf(commissionRates);
        closingFeeByMaxPricePaise = Collections.unmodifiableNavigableMap(closingFeeByMaxPricePaise);
    }

    /** True when this card says the order attracts no commission at all. */
    public boolean commissionExempt(long itemPricePaise, ProductCategory category) {
        return itemPricePaise < commissionFreeBelowPaise
                || alwaysFreeCategories.contains(category);
    }
}
