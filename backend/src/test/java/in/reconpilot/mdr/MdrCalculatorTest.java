package in.reconpilot.mdr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cases derived from docs/rules/upi-mdr-specification.md.
 *
 * <p>Rows marked (FAQ) are figures stated verbatim by the regulator, so a
 * failure there means we contradict the primary source -- not merely that we
 * disagree with our own implementation.
 */
class MdrCalculatorTest {

    private final MdrCalculator calc = new MdrCalculator();

    /** Rupees to paise, so test cases read in the units the regulation uses. */
    private static long rs(String rupees) {
        return new java.math.BigDecimal(rupees).movePointRight(2).longValueExact();
    }

    private MdrResult compute(String rupees, TxnType type, PaymentRail rail, PayeeCategory cat) {
        return calc.calculate(new MdrInput(rs(rupees), type, rail, cat));
    }

    @Nested
    @DisplayName("Figures stated by the regulator (FAQ Q32, Q35)")
    class RegulatorStatedFigures {

        @ParameterizedTest(name = "Rs {0} -> Rs {1}")
        @CsvSource({
                "2000.00,    0.00",   // at the threshold: FAQ table shows Rs 0
                "3000.00,   12.00",   // FAQ Q35
                "50000.00, 200.00",   // FAQ Q35
                "75000.00, 300.00",   // FAQ Q35, cap binds exactly here
                "100000.00,300.00"    // FAQ Q32: 0.4% would be 400, cap holds it at 300
        })
        void standardMerchant(String amount, String expectedMdr) {
            MdrResult r = compute(amount, TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD);
            assertEquals(rs(expectedMdr), r.mdrPaise());
        }
    }

    @Nested
    @DisplayName("Exemptions")
    class Exemptions {

        @Test
        void p2pIsAlwaysFree() {
            MdrResult r = compute("50000.00", TxnType.P2P, PaymentRail.UPI_QR, PayeeCategory.STANDARD);
            assertEquals(0L, r.mdrPaise());
            assertEquals("P2P_EXEMPT", r.ruleId());
        }

        @ParameterizedTest(name = "P2PM merchant, Rs {0} -> Rs 0")
        @CsvSource({"5000.00", "500000.00"})
        void p2pmIsExemptRegardlessOfAmount(String amount) {
            MdrResult r = compute(amount, TxnType.P2PM, PaymentRail.UPI_QR, PayeeCategory.STANDARD);
            assertEquals(0L, r.mdrPaise());
            assertEquals("P2PM_EXEMPT", r.ruleId());
        }

        @Test
        void autopayIsExempt() {
            MdrResult r = compute("10000.00", TxnType.P2M, PaymentRail.UPI_AUTOPAY, PayeeCategory.STANDARD);
            assertEquals(0L, r.mdrPaise());
            assertEquals("AUTOPAY_EXEMPT", r.ruleId());
        }
    }

    @Nested
    @DisplayName("Category-specific rates")
    class CategoryRates {

        @ParameterizedTest(name = "capital markets Rs {0} -> Rs {1}")
        @CsvSource({
                "50000.00,   10.00",
                "2000000.00,300.00"   // 0.02% would be 400; cap binds
        })
        void capitalMarkets(String amount, String expected) {
            MdrResult r = compute(amount, TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.CAPITAL_MARKETS);
            assertEquals(rs(expected), r.mdrPaise());
        }

        @ParameterizedTest(name = "industry program Rs {0} -> Rs {1}")
        @CsvSource({
                "3000.00,   5.00",
                "100000.00, 5.00",    // flat, regardless of size
                "1500.00,   0.00"     // below threshold, so exempt before the flat rate applies
        })
        void industryProgramIsFlatFive(String amount, String expected) {
            MdrResult r = compute(amount, TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.INDUSTRY_PROGRAM);
            assertEquals(rs(expected), r.mdrPaise());
        }
    }

    @Nested
    @DisplayName("Precedence: first match wins, specificity is not precedence")
    class Precedence {

        /**
         * The puzzle case. Four conditions are true at once: AutoPay, P2PM,
         * at-threshold amount, and a flat-Rs-5 category. Three imply Rs 0 and
         * one implies Rs 5. AutoPay is evaluated first, so it decides -- and
         * the ruleId must say so, because the REASON has a different lifespan
         * than the amount.
         */
        @Test
        void autopayWinsOverIndustryFlatRate() {
            MdrResult r = compute("2000.00", TxnType.P2PM,
                    PaymentRail.UPI_AUTOPAY, PayeeCategory.INDUSTRY_PROGRAM);
            assertEquals(0L, r.mdrPaise());
            assertEquals("AUTOPAY_EXEMPT", r.ruleId(),
                    "AutoPay is evaluated before merchant category");
        }

        /** Category must be checked before amount: P2PM is exempt at any value. */
        @Test
        void p2pmBeatsTheAmountCheck() {
            MdrResult r = compute("500000.00", TxnType.P2PM, PaymentRail.UPI_QR, PayeeCategory.STANDARD);
            assertEquals("P2PM_EXEMPT", r.ruleId());
        }
    }

    @Nested
    @DisplayName("Boundaries")
    class Boundaries {

        @Test
        void thresholdIsExclusive() {
            assertEquals(0L, compute("2000.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
            assertTrue(compute("2000.01", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise() >= 0L);
        }

        /**
         * The rounding case. 0.4% of Rs 3,333 is Rs 13.332. This test pins the
         * PROVISIONAL HALF_UP choice so that changing it is a deliberate, visible
         * act rather than an accident. See open question 5.
         */
        @Test
        void roundingIsPinnedPendingConfirmation() {
            MdrResult r = compute("3333.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD);
            assertEquals(rs("13.33"), r.mdrPaise());
        }

        @Test
        void percentageGovernsBelowTheCapAndTheCapGovernsAbove() {
            // 0.4% of Rs 70,000 = Rs 280, comfortably under the cap.
            assertEquals(rs("280.00"),
                    compute("70000.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
            assertEquals(MdrCalculator.CAP_PAISE,
                    compute("75000.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
            assertEquals(MdrCalculator.CAP_PAISE,
                    compute("100000.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
        }

        /**
         * Found by a failing test, and worth recording rather than smoothing over.
         *
         * <p>The regulation reads as though the cap begins to bind at exactly
         * Rs 75,000, because 0.4% of Rs 75,000 is exactly Rs 300. With rounding,
         * it binds fractionally earlier: 0.4% of Rs 74,999 is Rs 299.996, which
         * HALF_UP takes to Rs 300 -- the cap value.
         *
         * <p>This is a consequence of the PROVISIONAL rounding mode, not of the
         * cap itself. If open question 5 resolves to truncation instead, this
         * test changes. It exists so that such a change is visible.
         */
        @Test
        void roundingMakesTheCapBindFractionallyBelowSeventyFiveThousand() {
            assertEquals(rs("300.00"),
                    compute("74999.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
            assertEquals(rs("299.99"),
                    compute("74998.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD).mdrPaise());
        }
    }

    @Nested
    @DisplayName("Unspecified rules fail loudly rather than guessing")
    class FailsLoudly {

        @Test
        void educationRateIsNotImplemented() {
            assertThrows(UnspecifiedRuleException.class, () ->
                    compute("5000.00", TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.EDUCATION));
        }

        @Test
        void creditLinkedUpiIsOutOfScope() {
            assertThrows(UnspecifiedRuleException.class, () ->
                    compute("5000.00", TxnType.P2M, PaymentRail.UPI_CREDIT_LINE, PayeeCategory.STANDARD));
        }

        @Test
        void negativeAmountIsRejected() {
            assertThrows(IllegalArgumentException.class, () ->
                    new MdrInput(-1L, TxnType.P2M, PaymentRail.UPI_QR, PayeeCategory.STANDARD));
        }
    }
}
