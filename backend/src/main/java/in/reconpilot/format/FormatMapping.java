package in.reconpilot.format;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A stored, validated mapping. Once this exists for a header fingerprint, no
 * model is involved in reading that format again.
 *
 * @param columnMap    canonical field name -> source column name
 * @param valueAliases "FIELD|sourceValue" -> canonical value
 */
public record FormatMapping(
        UUID id,
        String sourceLabel,
        String headerFingerprint,
        Map<String, String> columnMap,
        AmountUnit amountUnit,
        Map<String, String> valueAliases,
        String status,
        String model,
        Double confidence,
        String validationNotes,
        Instant createdAt,
        Instant validatedAt
) {
    public boolean isUsable() {
        return "VALIDATED".equals(status);
    }
}
