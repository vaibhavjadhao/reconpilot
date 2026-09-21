package in.reconpilot.format;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * What the model returns. Shaped as a record so the SDK derives the JSON
 * schema from it and returns a typed object rather than a string we have to
 * parse and hope about.
 *
 * <p>Note what is <b>not</b> here: no amounts, no computed fees, no decisions.
 * The model describes the file's shape. Every number is still read from the
 * file by our own code and computed by {@code MdrCalculator}.
 */
public record MappingProposal(

        @JsonPropertyDescription("One entry per canonical field you can identify. Omit fields the file does not contain.")
        List<FieldMapping> mappings,

        @JsonPropertyDescription("PAISE if amounts are whole paise, RUPEES if they are rupees (possibly with decimals).")
        String amountUnit,

        @JsonPropertyDescription("Translations for coded values, e.g. source 'QR' means canonical 'UPI_QR'. Empty if none are needed.")
        List<ValueAlias> valueAliases,

        @JsonPropertyDescription("Anything ambiguous or worth a human knowing. One or two sentences.")
        String notes
) {
    public record FieldMapping(
            @JsonPropertyDescription("One of: EXTERNAL_TXN_ID, MERCHANT_REF, AMOUNT, TXN_TYPE, RAIL, PAYEE_CATEGORY, CHARGED_MDR, OCCURRED_AT")
            String canonicalField,
            @JsonPropertyDescription("The column header from the file, copied exactly.")
            String sourceColumn,
            @JsonPropertyDescription("0.0 to 1.0. Be honest: a low score is more useful than a confident guess.")
            double confidence
    ) {}

    public record ValueAlias(
            @JsonPropertyDescription("Which canonical field this translation applies to.")
            String canonicalField,
            @JsonPropertyDescription("The value as it appears in the file.")
            String sourceValue,
            @JsonPropertyDescription("The canonical value it corresponds to.")
            String canonicalValue
    ) {}
}
