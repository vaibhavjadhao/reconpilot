package in.reconpilot.format;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The validator is the only thing standing between a confident wrong answer
 * from a language model and a million mis-parsed rows, so it is tested harder
 * than anything it guards.
 *
 * <p>No Spring, no network, no model. Every case here is a proposal that looks
 * entirely plausible and is wrong in one specific way.
 */
class MappingValidatorTest {

    private final MappingValidator validator = new MappingValidator();

    private static final List<String> HEADER = List.of(
            "txn_ref", "vpa", "amount", "type", "channel", "category", "fee", "ts");

    private static Map<String, String> row(String amount, String fee, String type,
                                           String channel, String category, String ts) {
        return Map.of("txn_ref", "T1", "vpa", "shop@upi", "amount", amount, "type", type,
                      "channel", channel, "category", category, "fee", fee, "ts", ts);
    }

    private static Map<String, String> goodRow() {
        return row("300000", "1200", "P2M", "QR", "STANDARD", "2026-10-15T10:00:00Z");
    }

    private static MappingProposal proposal(String amountUnit,
                                            List<MappingProposal.ValueAlias> aliases) {
        return new MappingProposal(List.of(
                new MappingProposal.FieldMapping("EXTERNAL_TXN_ID", "txn_ref", 0.98),
                new MappingProposal.FieldMapping("MERCHANT_REF", "vpa", 0.97),
                new MappingProposal.FieldMapping("AMOUNT", "amount", 0.95),
                new MappingProposal.FieldMapping("CHARGED_MDR", "fee", 0.92),
                new MappingProposal.FieldMapping("TXN_TYPE", "type", 0.9),
                new MappingProposal.FieldMapping("RAIL", "channel", 0.85),
                new MappingProposal.FieldMapping("PAYEE_CATEGORY", "category", 0.88),
                new MappingProposal.FieldMapping("OCCURRED_AT", "ts", 0.99)),
                amountUnit, aliases, "looks like a standard PSP export");
    }

    private static final List<MappingProposal.ValueAlias> QR_ALIAS =
            List.of(new MappingProposal.ValueAlias("RAIL", "QR", "UPI_QR"));

    @Test
    void acceptsACorrectMapping() {
        ValidationResult r = validator.validate(
                proposal("PAISE", QR_ALIAS), HEADER, List.of(goodRow()));
        assertTrue(r.ok(), () -> "unexpected problems: " + r.problems());
    }

    @Nested
    @DisplayName("Structural faults")
    class Structural {

        @Test
        void rejectsAColumnThatDoesNotExistInTheFile() {
            MappingProposal p = new MappingProposal(List.of(
                    new MappingProposal.FieldMapping("AMOUNT", "settlement_value", 0.99)),
                    "PAISE", List.of(), null);

            ValidationResult r = validator.validate(p, HEADER, List.of(goodRow()));

            assertFalse(r.ok());
            assertTrue(r.problems().stream().anyMatch(s -> s.contains("does not exist")),
                    () -> r.problems().toString());
        }

        @Test
        void rejectsAMappingMissingARequiredField() {
            MappingProposal p = new MappingProposal(List.of(
                    new MappingProposal.FieldMapping("EXTERNAL_TXN_ID", "txn_ref", 0.9),
                    new MappingProposal.FieldMapping("MERCHANT_REF", "vpa", 0.9),
                    new MappingProposal.FieldMapping("AMOUNT", "amount", 0.9),
                    new MappingProposal.FieldMapping("OCCURRED_AT", "ts", 0.9)),
                    "PAISE", List.of(), null);   // CHARGED_MDR absent

            ValidationResult r = validator.validate(p, HEADER, List.of(goodRow()));

            assertFalse(r.ok());
            assertTrue(r.problems().stream().anyMatch(s -> s.contains("CHARGED_MDR")));
        }

        @Test
        void rejectsAnInventedCanonicalField() {
            MappingProposal p = new MappingProposal(List.of(
                    new MappingProposal.FieldMapping("SETTLEMENT_BATCH", "txn_ref", 0.9)),
                    "PAISE", List.of(), null);

            assertTrue(validator.validate(p, HEADER, List.of(goodRow())).problems().stream()
                    .anyMatch(s -> s.contains("Unknown canonical field")));
        }

        @Test
        void refusesToValidateAgainstNoRows() {
            ValidationResult r = validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of());
            assertFalse(r.ok(), "a mapping cannot be checked against nothing");
        }
    }

    @Nested
    @DisplayName("The factor-of-100 error")
    class AmountUnits {

        /**
         * The dangerous case: the mapping is structurally perfect and every
         * column is right. Only the unit is wrong, and being wrong by 100x in
         * every figure is the worst failure this product can have.
         */
        @Test
        void catchesRupeesDeclaredAsPaiseWhenTheDecimalsGiveItAway() {
            // "3000.50" rupees is 300050 paise; read as PAISE it cannot be whole.
            Map<String, String> r = row("3000.505", "12.005", "P2M", "QR", "STANDARD",
                                        "2026-10-15T10:00:00Z");

            ValidationResult result = validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of(r));

            assertFalse(result.ok());
            assertTrue(result.problems().stream().anyMatch(s -> s.contains("unit is probably wrong")),
                    () -> result.problems().toString());
        }

        @Test
        void acceptsRupeesWhenDeclaredAsRupees() {
            Map<String, String> r = row("3000.50", "12.00", "P2M", "QR", "STANDARD",
                                        "2026-10-15T10:00:00Z");
            assertTrue(validator.validate(proposal("RUPEES", QR_ALIAS), HEADER, List.of(r)).ok());
        }

        @Test
        void rejectsAnUnknownUnit() {
            assertTrue(validator.validate(proposal("CENTS", QR_ALIAS), HEADER, List.of(goodRow()))
                    .problems().stream().anyMatch(s -> s.contains("amountUnit")));
        }

        @Test
        void rejectsANonNumericAmount() {
            Map<String, String> r = row("N/A", "1200", "P2M", "QR", "STANDARD",
                                        "2026-10-15T10:00:00Z");
            assertTrue(validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of(r))
                    .problems().stream().anyMatch(s -> s.contains("is not a number")));
        }

        @Test
        void rejectsANegativeAmount() {
            Map<String, String> r = row("-500", "1200", "P2M", "QR", "STANDARD",
                                        "2026-10-15T10:00:00Z");
            assertTrue(validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of(r))
                    .problems().stream().anyMatch(s -> s.contains("negative")));
        }
    }

    @Nested
    @DisplayName("Coded values")
    class Aliases {

        /** Without the alias, "QR" is not a PaymentRail and the mapping is unusable. */
        @Test
        void rejectsACodedValueWithNoTranslation() {
            ValidationResult r = validator.validate(
                    proposal("PAISE", List.of()), HEADER, List.of(goodRow()));

            assertFalse(r.ok());
            assertTrue(r.problems().stream().anyMatch(s -> s.contains("not a valid PaymentRail")),
                    () -> r.problems().toString());
        }

        @Test
        void rejectsATranslationToAValueThatDoesNotExist() {
            var badAlias = List.of(new MappingProposal.ValueAlias("RAIL", "QR", "UPI_QRCODE"));
            assertFalse(validator.validate(proposal("PAISE", badAlias), HEADER, List.of(goodRow())).ok());
        }

        @Test
        void acceptsAValueThatAlreadyMatchesWithoutAnAlias() {
            Map<String, String> r = row("300000", "1200", "P2M", "UPI_QR", "STANDARD",
                                        "2026-10-15T10:00:00Z");
            assertTrue(validator.validate(proposal("PAISE", List.of()), HEADER, List.of(r)).ok());
        }

        @Test
        void matchesCaseInsensitivelyWithoutAnAlias() {
            Map<String, String> r = row("300000", "1200", "p2m", "upi_qr", "standard",
                                        "2026-10-15T10:00:00Z");
            assertTrue(validator.validate(proposal("PAISE", List.of()), HEADER, List.of(r)).ok());
        }
    }

    @Nested
    @DisplayName("Timestamps and blanks")
    class Other {

        @Test
        void rejectsANonIsoTimestamp() {
            Map<String, String> r = row("300000", "1200", "P2M", "QR", "STANDARD", "15/10/2026 10:00");
            assertTrue(validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of(r))
                    .problems().stream().anyMatch(s -> s.contains("ISO-8601")));
        }

        @Test
        void rejectsAnEmptyRequiredValue() {
            Map<String, String> r = row("300000", "1200", "P2M", "QR", "STANDARD", "");
            assertFalse(validator.validate(proposal("PAISE", QR_ALIAS), HEADER, List.of(r)).ok());
        }

        /** One bad row in a sample is enough to reject; it will not be the only one. */
        @Test
        void oneBadRowAmongGoodOnesStillFails() {
            List<Map<String, String>> rows = List.of(
                    goodRow(), goodRow(),
                    row("oops", "1200", "P2M", "QR", "STANDARD", "2026-10-15T10:00:00Z"),
                    goodRow());
            assertFalse(validator.validate(proposal("PAISE", QR_ALIAS), HEADER, rows).ok());
        }

        @Test
        void reportsEveryProblemNotJustTheFirst() {
            Map<String, String> r = row("oops", "also-oops", "NOPE", "QR", "STANDARD", "not-a-date");
            ValidationResult result = validator.validate(proposal("PAISE", List.of()), HEADER, List.of(r));
            assertTrue(result.problems().size() >= 4,
                    () -> "a human fixing this wants the whole list: " + result.problems());
        }
    }
}
