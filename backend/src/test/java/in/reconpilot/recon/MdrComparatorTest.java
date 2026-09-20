package in.reconpilot.recon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The categories overlap, so these tests are mostly about precedence: which
 * label wins when more than one is arguably true.
 */
class MdrComparatorTest {

    private final MdrComparator comparator = new MdrComparator();

    @Test
    void matchingAmountsAreNotABreak() {
        ReconOutcome r = comparator.compare(1200, 1200);
        assertEquals(BreakType.NONE, r.type());
        assertEquals(0, r.deltaPaise());
        assertFalse(r.isBreak());
    }

    @Nested
    @DisplayName("Precedence: the more specific label wins")
    class Precedence {

        /**
         * Rs 50 charged where nothing was due is arithmetically an overcharge,
         * but "you charged an exempt merchant" cites a specific rule, where
         * "you charged too much" invites an argument about rounding.
         */
        @Test
        void chargingAnExemptTransactionBeatsPlainOvercharge() {
            ReconOutcome r = comparator.compare(0, 5000);
            assertEquals(BreakType.CHARGED_WHEN_EXEMPT, r.type());
            assertEquals(5000, r.deltaPaise());
        }

        /** Above the Rs 300 cap is a citable breach, not merely "too much". */
        @Test
        void breachingTheCapBeatsPlainOvercharge() {
            ReconOutcome r = comparator.compare(30_000, 40_000);
            assertEquals(BreakType.CAP_BREACHED, r.type());
            assertEquals(10_000, r.deltaPaise());
        }

        /**
         * Both exempt AND above the cap. Exempt is checked first because it is
         * the stronger claim: nothing at all was owed.
         */
        @Test
        void exemptBeatsCapBreachWhenBothApply() {
            assertEquals(BreakType.CHARGED_WHEN_EXEMPT, comparator.compare(0, 45_000).type());
        }

        /** Under the cap and over the expectation is an ordinary overcharge. */
        @Test
        void overchargeUnderTheCapIsPlainOvercharge() {
            ReconOutcome r = comparator.compare(1200, 1800);
            assertEquals(BreakType.OVERCHARGED, r.type());
            assertEquals(600, r.deltaPaise());
        }
    }

    @Nested
    @DisplayName("Undercharges are reported but never claimed")
    class Undercharges {

        @Test
        void shortfallIsClassifiedAndNegative() {
            ReconOutcome r = comparator.compare(1200, 900);
            assertEquals(BreakType.UNDERCHARGED, r.type());
            assertEquals(-300, r.deltaPaise());
        }

        /** An undercharge is the PSP's money, not ours. */
        @Test
        void nothingIsRecoverableFromAnUndercharge() {
            assertEquals(0, comparator.compare(1200, 900).recoverablePaise());
        }

        @Test
        void anOverchargeIsFullyRecoverable() {
            assertEquals(600, comparator.compare(1200, 1800).recoverablePaise());
        }
    }

    @Nested
    @DisplayName("Boundaries")
    class Boundaries {

        /** Exactly at the cap is correct, not a breach. */
        @Test
        void chargingExactlyTheCapIsNotABreach() {
            assertEquals(BreakType.NONE, comparator.compare(30_000, 30_000).type());
        }

        /** One paise over the cap is a breach. This is the whole point of D7. */
        @Test
        void onePaiseOverTheCapIsABreach() {
            assertEquals(BreakType.CAP_BREACHED, comparator.compare(30_000, 30_001).type());
        }

        @Test
        void bothZeroIsNotABreak() {
            assertEquals(BreakType.NONE, comparator.compare(0, 0).type());
        }
    }
}
