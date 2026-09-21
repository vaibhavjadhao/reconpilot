package in.reconpilot.format;

import in.reconpilot.mdr.PayeeCategory;
import in.reconpilot.mdr.PaymentRail;
import in.reconpilot.mdr.TxnType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Checks a proposed mapping against real rows from the file it claims to
 * describe.
 *
 * <h2>Why this exists</h2>
 *
 * A language model is non-deterministic: the same file could in principle
 * produce a different answer tomorrow. Everything else in this system has been
 * built to be deterministic, replayable and auditable, and a plausible-looking
 * mapping is worth nothing on its own.
 *
 * <p>So the model's output is treated as a <b>proposal</b>, never as truth. It
 * becomes usable only after this class has read actual rows through it and
 * confirmed every value parses. That inverts the trust relationship: the model
 * suggests, deterministic code decides.
 *
 * <p>This class has no dependency on the model or the network, so it is fully
 * unit-testable -- which matters, because it is the only thing standing between
 * a confident wrong answer and a million mis-parsed rows.
 */
@Component
public class MappingValidator {

    /** Without these a file cannot be reconciled at all. */
    private static final Set<CanonicalField> REQUIRED = EnumSet.of(
            CanonicalField.EXTERNAL_TXN_ID,
            CanonicalField.MERCHANT_REF,
            CanonicalField.AMOUNT,
            CanonicalField.CHARGED_MDR,
            CanonicalField.OCCURRED_AT);

    /** Rows of the real file, already split into columns, keyed by header. */
    public ValidationResult validate(MappingProposal proposal,
                                     List<String> header,
                                     List<Map<String, String>> sampleRows) {
        List<String> problems = new ArrayList<>();

        if (sampleRows.isEmpty()) {
            return ValidationResult.fail(List.of("No sample rows supplied; a mapping cannot be checked against nothing."));
        }

        // --- the proposal must be structurally sane -------------------------
        Map<CanonicalField, String> columnMap = new EnumMap<>(CanonicalField.class);
        for (MappingProposal.FieldMapping m : nullSafe(proposal.mappings())) {
            CanonicalField field;
            try {
                field = CanonicalField.valueOf(m.canonicalField().trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                problems.add("Unknown canonical field '" + m.canonicalField() + "'");
                continue;
            }
            if (!header.contains(m.sourceColumn())) {
                problems.add("Mapped column '" + m.sourceColumn() + "' for " + field
                        + " does not exist in the file header");
                continue;
            }
            columnMap.put(field, m.sourceColumn());
        }

        for (CanonicalField required : REQUIRED) {
            if (!columnMap.containsKey(required)) {
                problems.add("Required field " + required + " was not mapped to any column");
            }
        }

        AmountUnit unit;
        try {
            unit = AmountUnit.valueOf(String.valueOf(proposal.amountUnit()).trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            problems.add("amountUnit must be PAISE or RUPEES, got '" + proposal.amountUnit() + "'");
            unit = AmountUnit.PAISE;
        }

        Map<String, String> aliases = aliasIndex(proposal);

        // --- and it must actually work on real rows -------------------------
        // Structural checks only prove the proposal is well formed. Reading the
        // file through it is what proves it is right.
        int row = 0;
        for (Map<String, String> r : sampleRows) {
            row++;
            checkAmount(columnMap, unit, r, CanonicalField.AMOUNT, row, problems);
            checkAmount(columnMap, unit, r, CanonicalField.CHARGED_MDR, row, problems);
            checkTimestamp(columnMap, r, row, problems);
            checkEnum(columnMap, aliases, r, CanonicalField.TXN_TYPE, TxnType.class, row, problems);
            checkEnum(columnMap, aliases, r, CanonicalField.RAIL, PaymentRail.class, row, problems);
            checkEnum(columnMap, aliases, r, CanonicalField.PAYEE_CATEGORY, PayeeCategory.class, row, problems);
            checkPresent(columnMap, r, CanonicalField.EXTERNAL_TXN_ID, row, problems);
            checkPresent(columnMap, r, CanonicalField.MERCHANT_REF, row, problems);
            if (problems.size() > 20) {              // enough to diagnose; no point listing thousands
                problems.add("... further problems suppressed");
                break;
            }
        }

        return problems.isEmpty() ? ValidationResult.pass() : ValidationResult.fail(problems);
    }

    // ------------------------------------------------------------- checks --

    private void checkAmount(Map<CanonicalField, String> map, AmountUnit unit,
                             Map<String, String> row, CanonicalField field,
                             int rowNo, List<String> problems) {
        String col = map.get(field);
        if (col == null) return;
        String raw = row.get(col);
        if (raw == null || raw.isBlank()) {
            problems.add("Row " + rowNo + ": " + field + " ('" + col + "') is empty");
            return;
        }
        try {
            long paise = unit.toPaise(raw);
            if (paise < 0) {
                problems.add("Row " + rowNo + ": " + field + " is negative (" + raw + ")");
            }
        } catch (ArithmeticException e) {
            // A decimal that cannot become whole paise means the unit is wrong,
            // e.g. rupees with three decimal places, or paise read as rupees.
            problems.add("Row " + rowNo + ": " + field + " value '" + raw
                    + "' does not convert to whole paise as " + unit + " -- the unit is probably wrong");
        } catch (NumberFormatException e) {
            problems.add("Row " + rowNo + ": " + field + " value '" + raw + "' is not a number");
        }
    }

    private void checkTimestamp(Map<CanonicalField, String> map, Map<String, String> row,
                                int rowNo, List<String> problems) {
        String col = map.get(CanonicalField.OCCURRED_AT);
        if (col == null) return;
        String raw = row.get(col);
        if (raw == null || raw.isBlank()) {
            problems.add("Row " + rowNo + ": OCCURRED_AT ('" + col + "') is empty");
            return;
        }
        try {
            Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            problems.add("Row " + rowNo + ": OCCURRED_AT '" + raw + "' is not an ISO-8601 instant");
        }
    }

    private <E extends Enum<E>> void checkEnum(Map<CanonicalField, String> map, Map<String, String> aliases,
                                               Map<String, String> row, CanonicalField field,
                                               Class<E> type, int rowNo, List<String> problems) {
        String col = map.get(field);
        if (col == null) return;                     // optional field, not mapped
        String raw = row.get(col);
        if (raw == null || raw.isBlank()) return;    // absent value is not a mapping fault

        String resolved = aliases.getOrDefault(field + "|" + raw.trim(), raw.trim().toUpperCase());
        try {
            Enum.valueOf(type, resolved);
        } catch (IllegalArgumentException e) {
            problems.add("Row " + rowNo + ": " + field + " value '" + raw + "' resolves to '"
                    + resolved + "', which is not a valid " + type.getSimpleName());
        }
    }

    private void checkPresent(Map<CanonicalField, String> map, Map<String, String> row,
                              CanonicalField field, int rowNo, List<String> problems) {
        String col = map.get(field);
        if (col == null) return;
        String raw = row.get(col);
        if (raw == null || raw.isBlank()) {
            problems.add("Row " + rowNo + ": " + field + " ('" + col + "') is empty");
        }
    }

    // ------------------------------------------------------------ helpers --

    /** "FIELD|sourceValue" -> canonical value. */
    static Map<String, String> aliasIndex(MappingProposal proposal) {
        Map<String, String> index = new HashMap<>();
        for (MappingProposal.ValueAlias a : nullSafe(proposal.valueAliases())) {
            if (a.canonicalField() == null || a.sourceValue() == null || a.canonicalValue() == null) continue;
            index.put(a.canonicalField().trim().toUpperCase() + "|" + a.sourceValue().trim(),
                      a.canonicalValue().trim().toUpperCase());
        }
        return index;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
