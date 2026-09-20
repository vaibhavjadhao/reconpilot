package in.reconpilot.recon;

import in.reconpilot.mdr.MdrCalculator;
import org.springframework.stereotype.Component;

/**
 * Compares what was charged against what the rules require.
 *
 * <p>As in {@link MdrCalculator}, the order of these checks is part of the
 * contract rather than an implementation detail, because the categories
 * overlap. A charge of Rs 400 where Rs 300 was due is simultaneously a cap
 * breach and an overcharge; a charge of Rs 50 where nothing was due is
 * simultaneously charging-when-exempt and an overcharge.
 *
 * <p>The more specific classification wins, because it makes the stronger
 * claim. "You charged a merchant who is exempt" and "you exceeded the
 * regulator's cap" are both citable to a specific rule. "You charged slightly
 * too much" invites an argument about rounding.
 */
@Component
public class MdrComparator {

    public ReconOutcome compare(long expectedPaise, long chargedPaise) {
        long delta = chargedPaise - expectedPaise;

        if (delta == 0) {
            return new ReconOutcome(BreakType.NONE, 0);
        }
        if (expectedPaise == 0 && chargedPaise > 0) {
            return new ReconOutcome(BreakType.CHARGED_WHEN_EXEMPT, delta);
        }
        if (chargedPaise > MdrCalculator.CAP_PAISE) {
            return new ReconOutcome(BreakType.CAP_BREACHED, delta);
        }
        if (delta > 0) {
            return new ReconOutcome(BreakType.OVERCHARGED, delta);
        }
        return new ReconOutcome(BreakType.UNDERCHARGED, delta);
    }
}
