package in.reconpilot.marketplace;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the seller should have been charged, component by component.
 *
 * <p>The important field is {@link #unverifiable()}. A reconciliation tool
 * that cannot distinguish "I checked this and it is correct" from "I had no
 * rate to check this against" will eventually report the second as the first,
 * and a seller who acts on that is disputing a charge nobody can defend. Every
 * figure here is either backed by a published rate or declared unchecked.
 *
 * @param commissionPaise  expected commission
 * @param closingFeePaise  expected flat fee
 * @param gstPaise         expected tax on the VERIFIED components only
 * @param verified         components computed from a published rate
 * @param unverifiable     components with no citable rate, so not compared
 * @param rule             why commission came out as it did, for the audit trail
 */
public record FeeBreakdown(
        long commissionPaise,
        long closingFeePaise,
        long gstPaise,
        Set<FeeComponent> verified,
        Set<FeeComponent> unverifiable,
        String rule
) {
    public FeeBreakdown {
        verified = Set.copyOf(verified);
        unverifiable = Set.copyOf(unverifiable);
    }

    /**
     * Total of the components we can actually stand behind.
     *
     * <p>Deliberately not "the total fee". The seller's statement total
     * includes shipping and collection, which we did not verify, so comparing
     * against a grand total would flag every row.
     */
    public long verifiedTotalPaise() {
        return commissionPaise + closingFeePaise + gstPaise;
    }

    public boolean fullyVerified() {
        return unverifiable.isEmpty();
    }

    static Set<FeeComponent> none() {
        return EnumSet.noneOf(FeeComponent.class);
    }
}
