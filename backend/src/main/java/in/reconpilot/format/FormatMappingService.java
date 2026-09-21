package in.reconpilot.format;

import in.reconpilot.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;

/**
 * Finds or discovers the mapping for a settlement file's format.
 *
 * <p>The cache key is a fingerprint of the <b>header row</b>, not of the file:
 * two different months of the same PSP's statement share a fingerprint and
 * therefore a mapping. Discovery happens once per format, ever.
 */
@Service
public class FormatMappingService {

    private static final Logger log = LoggerFactory.getLogger(FormatMappingService.class);

    private final JdbcTemplate jdbc;
    private final FormatDiscoveryService discovery;
    private final MappingValidator validator;
    private final JsonMapper json;

    public FormatMappingService(JdbcTemplate jdbc, FormatDiscoveryService discovery,
                                MappingValidator validator, JsonMapper json) {
        this.jdbc = jdbc;
        this.discovery = discovery;
        this.validator = validator;
        this.json = json;
    }

    /**
     * Identifies a format by its header row.
     *
     * <p>Normalised so that harmless variation -- case, surrounding whitespace,
     * a trailing empty column -- does not look like a new format and trigger a
     * needless discovery call.
     */
    public static String fingerprint(List<String> header) {
        String normalised = header.stream()
                .map(h -> h == null ? "" : h.trim().toLowerCase())
                .filter(h -> !h.isEmpty())
                .reduce((a, b) -> a + "\u001f" + b)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalised.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Optional<FormatMapping> find(List<String> header) {
        return jdbc.query("""
                SELECT id, source_label, header_fingerprint, column_map, amount_unit,
                       value_aliases, status, model, confidence, validation_notes,
                       created_at, validated_at
                  FROM format_mapping
                 WHERE header_fingerprint = ?
                """, this::mapRow, fingerprint(header)).stream().findFirst();
    }

    /**
     * Returns the stored mapping, discovering one if this format has not been
     * seen before.
     *
     * <p>A proposal is stored whatever the outcome. A REJECTED row is more
     * useful than no row: it records what was tried and why it failed, and stops
     * the next upload of the same format silently spending another API call to
     * fail the same way.
     */
    public FormatMapping findOrDiscover(String sourceLabel, List<String> header,
                                        List<Map<String, String>> sampleRows) {
        Optional<FormatMapping> existing = find(header);
        if (existing.isPresent()) {
            log.info("Format '{}' already known ({}), no model call", sourceLabel, existing.get().status());
            return existing.get();
        }

        log.info("Unknown format '{}', asking {} to map {} columns",
                sourceLabel, discovery.model(), header.size());

        MappingProposal proposal = discovery.propose(header, sampleRows);
        ValidationResult validation = validator.validate(proposal, header, sampleRows);

        if (!validation.ok()) {
            log.warn("Proposed mapping for '{}' rejected: {}", sourceLabel, validation.summary());
        }

        return store(sourceLabel, header, proposal, validation);
    }

    private FormatMapping store(String sourceLabel, List<String> header,
                                MappingProposal proposal, ValidationResult validation) {
        Map<String, String> columnMap = new LinkedHashMap<>();
        double minConfidence = 1.0;
        for (MappingProposal.FieldMapping m : proposal.mappings() == null ? List.<MappingProposal.FieldMapping>of() : proposal.mappings()) {
            columnMap.put(m.canonicalField().trim().toUpperCase(), m.sourceColumn());
            minConfidence = Math.min(minConfidence, m.confidence());
        }

        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(java.time.Instant.now());
        String status = validation.ok() ? "VALIDATED" : "REJECTED";

        jdbc.update("""
                INSERT INTO format_mapping
                    (id, tenant_id, header_fingerprint, source_label, header_row,
                     column_map, amount_unit, value_aliases, status, model,
                     confidence, validation_notes, created_at, validated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, header_fingerprint) DO NOTHING
                """,
                id, TenantContext.get(), fingerprint(header), sourceLabel,
                String.join(",", header),
                json.writeValueAsString(columnMap),
                safeUnit(proposal.amountUnit()),
                json.writeValueAsString(MappingValidator.aliasIndex(proposal)),
                status, discovery.model(),
                // The weakest link, not the average: one bad column ruins the file.
                minConfidence,
                describe(proposal, validation),
                now, validation.ok() ? now : null);

        return find(header).orElseThrow(() ->
                new FormatDiscoveryException("Mapping was stored but could not be read back"));
    }

    private static String safeUnit(String raw) {
        try {
            return AmountUnit.valueOf(String.valueOf(raw).trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return "PAISE";
        }
    }

    private static String describe(MappingProposal proposal, ValidationResult validation) {
        String notes = proposal.notes() == null ? "" : proposal.notes().trim();
        return notes.isEmpty() ? validation.summary() : notes + " | " + validation.summary();
    }

    private FormatMapping mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new FormatMapping(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                readMap(rs.getString(4)),
                AmountUnit.valueOf(rs.getString(5)),
                readMap(rs.getString(6)),
                rs.getString(7),
                rs.getString(8),
                rs.getObject(9) == null ? null : rs.getDouble(9),
                rs.getString(10),
                rs.getTimestamp(11).toInstant(),
                rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant());
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMap(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        return json.readValue(raw, Map.class);
    }
}
