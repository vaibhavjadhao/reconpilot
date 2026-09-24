package in.reconpilot.marketplace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No Spring, no database, no container. The whole class runs in milliseconds
 * because the thing it tests is plain Java -- which is the point of keeping
 * the money logic free of framework annotations.
 */
class MarketplaceFeeCalculatorTest {

    private final MarketplaceFeeCalculator calc = new MarketplaceFeeCalculator();

    private static final LocalDate AFTER_FLIPKART_CHANGE = LocalDate.of(2026, 10, 1);
    private static final LocalDate AFTER_AMAZON_CHANGE   = LocalDate.of(2026, 4, 1);

    private static SettlementInput amazon(long pricePaise, ProductCategory cat, LocalDate on) {
        return new SettlementInput(Marketplace.AMAZON, on, pricePaise, cat,
                PaymentMode.PREPAID, ShippingZone.LOCAL, FulfilmentChannel.MARKETPLACE_SHIPPED);
    }

    private static SettlementInput flipkart(long pricePaise, ProductCategory cat, LocalDate on) {
        return new SettlementInput(Marketplace.FLIPKART, on, pricePaise, cat,
                PaymentMode.PREPAID, ShippingZone.LOCAL, FulfilmentChannel.MARKETPLACE_SHIPPED);
    }

    @Nested
    @DisplayName("Exemptions, which is where the real overcharges hide")
    class Exemptions {

        @Test
        @DisplayName("Amazon charges no commission below Rs 1,000")
        void amazonBelowThreshold() {
            var r = calc.calculate(amazon(99_900, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
            assertEquals(0, r.commissionPaise());
            assertEquals("COMMISSION_EXEMPT_BELOW_THRESHOLD", r.rule());
            assertTrue(r.verified().contains(FeeComponent.COMMISSION),
                    "a zero we can prove is a verified zero, not an unchecked one");
        }

        @Test
        @DisplayName("exactly Rs 1,000 is NOT exempt -- the band is strictly below")
        void thresholdIsExclusive() {
            var r = calc.calculate(amazon(100_000, ProductCategory.MOBILE_PHONES, AFTER_AMAZON_CHANGE));
            assertEquals(5_000, r.commissionPaise(), "5% of Rs 1,000 = Rs 50");
        }

        @Test
        @DisplayName("Flipkart charges no commission on fashion at ANY price")
        void flipkartFashionAlwaysFree() {
            var r = calc.calculate(flipkart(50_000_00L, ProductCategory.APPAREL, AFTER_FLIPKART_CHANGE));
            assertEquals(0, r.commissionPaise());
            assertEquals("COMMISSION_EXEMPT_CATEGORY", r.rule());
        }

        @Test
        @DisplayName("a category rate we cannot cite is reported unverifiable, never as zero")
        void unpublishedRateIsNotZero() {
            // Above the free band, non-fashion, and Flipkart's per-category
            // figures are not in any source we hold.
            var r = calc.calculate(flipkart(5_000_00L, ProductCategory.HOME_AND_KITCHEN, AFTER_FLIPKART_CHANGE));

            assertEquals("COMMISSION_RATE_UNPUBLISHED", r.rule());
            assertTrue(r.unverifiable().contains(FeeComponent.COMMISSION));
            assertFalse(r.verified().contains(FeeComponent.COMMISSION));
            assertFalse(r.fullyVerified());
        }
    }

    @Nested
    @DisplayName("Commission arithmetic")
    class Commission {

        @Test
        @DisplayName("mobile phones are 5%")
        void mobilePhones() {
            var r = calc.calculate(amazon(20_000_00L, ProductCategory.MOBILE_PHONES, AFTER_AMAZON_CHANGE));
            assertEquals(100_000, r.commissionPaise(), "5% of Rs 20,000 = Rs 1,000");
        }

        @Test
        @DisplayName("fashion jewellery is 22.5%")
        void fashionJewellery() {
            var r = calc.calculate(amazon(2_000_00L, ProductCategory.FASHION_JEWELLERY, AFTER_AMAZON_CHANGE));
            assertEquals(45_000, r.commissionPaise(), "22.5% of Rs 2,000 = Rs 450");
        }

        @Test
        @DisplayName("rounds half up, and the mode is pinned so changing it is deliberate")
        void rounding() {
            // 5% of Rs 1,234.51 = 61.7255 rupees = 6172.55 paise -> 6173
            var r = calc.calculate(amazon(123_451, ProductCategory.MOBILE_PHONES, AFTER_AMAZON_CHANGE));
            assertEquals(6_173, r.commissionPaise());
        }
    }

    @Nested
    @DisplayName("Closing fee slabs")
    class ClosingFee {

        @Test
        @DisplayName("up to Rs 300 is Rs 20")
        void lowSlab() {
            var r = calc.calculate(amazon(250_00L, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
            assertEquals(2_000, r.closingFeePaise());
        }

        @Test
        @DisplayName("Rs 300 itself falls in the first slab, not the second")
        void slabBoundaryIsInclusive() {
            var r = calc.calculate(amazon(300_00L, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
            assertEquals(2_000, r.closingFeePaise(), "the slab reads 'up to Rs 300'");
        }

        @Test
        @DisplayName("Rs 300 to Rs 500 is Rs 26")
        void middleSlab() {
            var r = calc.calculate(amazon(450_00L, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
            assertEquals(2_600, r.closingFeePaise());
        }

        @Test
        @DisplayName("above the published slabs the fee is unverifiable, not zero")
        void aboveTopSlab() {
            var r = calc.calculate(amazon(5_000_00L, ProductCategory.MOBILE_PHONES, AFTER_AMAZON_CHANGE));
            assertEquals(0, r.closingFeePaise());
            assertTrue(r.unverifiable().contains(FeeComponent.CLOSING_FEE));
        }
    }

    @Nested
    @DisplayName("GST")
    class Gst {

        @Test
        @DisplayName("is 18% of the FEES, not of the item price")
        void onFeesNotPrice() {
            // Rs 20,000 phone: commission Rs 1,000, no citable closing fee.
            var r = calc.calculate(amazon(20_000_00L, ProductCategory.MOBILE_PHONES, AFTER_AMAZON_CHANGE));

            assertEquals(18_000, r.gstPaise(), "18% of Rs 1,000 = Rs 180");
            // If it were charged on the item price it would be Rs 3,600 --
            // twenty times too much, and a seller would never spot it in a
            // statement of ten thousand lines.
            assertNotEquals(360_000, r.gstPaise());
        }

        @Test
        @DisplayName("covers commission and closing fee together")
        void overBothComponents() {
            // Rs 250 apparel: exempt commission (Rs 0) + closing Rs 20 = Rs 20.
            var r = calc.calculate(amazon(250_00L, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
            assertEquals(360, r.gstPaise(), "18% of Rs 20 = Rs 3.60");
            assertEquals(2_360, r.verifiedTotalPaise(), "Rs 20 + Rs 3.60");
        }
    }

    @Nested
    @DisplayName("Effective dating -- the mistake that would flag every row")
    class EffectiveDating {

        @Test
        @DisplayName("an order before the rate card existed is refused, not back-dated")
        void beforeAnyPublishedCard() {
            var tooEarly = amazon(20_000_00L, ProductCategory.MOBILE_PHONES, LocalDate.of(2026, 1, 1));

            UnpublishedRateException e =
                    assertThrows(UnpublishedRateException.class, () -> calc.calculate(tooEarly));
            assertTrue(e.getMessage().contains("2026-01-01"));
        }

        @Test
        @DisplayName("the card is chosen by ORDER date, so today's rates never leak backwards")
        void usesOrderDateNotToday() {
            var onTheDay = flipkart(50_000_00L, ProductCategory.APPAREL, LocalDate.of(2026, 9, 18));
            assertEquals(0, calc.calculate(onTheDay).commissionPaise(),
                    "the fashion exemption starts on the 18th itself");

            var dayBefore = flipkart(50_000_00L, ProductCategory.APPAREL, LocalDate.of(2026, 9, 17));
            assertThrows(UnpublishedRateException.class, () -> calc.calculate(dayBefore),
                    "we hold no Flipkart card for the 17th, and must not silently use the 18th's");
        }
    }

    @Test
    @DisplayName("a negative price is rejected at construction, not quietly computed")
    void negativePrice() {
        assertThrows(IllegalArgumentException.class,
                () -> amazon(-1, ProductCategory.APPAREL, AFTER_AMAZON_CHANGE));
    }
}
