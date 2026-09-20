package in.reconpilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Loads a settlement file into the append-only event log.
 *
 * <p>Three properties matter more than speed here:
 *
 * <ul>
 *   <li><b>Constant memory.</b> The file is hashed and parsed as a stream. A
 *       2 GB file costs the same heap as a 2 MB one.
 *   <li><b>Idempotency.</b> The file's SHA-256 is stored on the batch row under
 *       a unique constraint, so re-uploading identical bytes is rejected by
 *       PostgreSQL rather than by application logic someone can forget.
 *   <li><b>One recorded_at per batch.</b> Every row from one file shares a
 *       single "when we learned this" timestamp, so a replay filtered on
 *       recorded_at either includes the whole file or none of it. Calling
 *       now() per row would smear one file across time and make historical
 *       reconstruction ambiguous.
 * </ul>
 *
 * <p>Writes use {@link JdbcTemplate} batching rather than JPA. JPA is built for
 * managing a graph of domain objects through their lifecycle; these rows are
 * immutable facts that are written once and never updated. Using an ORM here
 * would add per-row overhead and a persistence context that grows with the file
 * -- reintroducing exactly the memory problem streaming just solved.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** Rows per round trip to the database. */
    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_EVENT = """
            INSERT INTO transaction_event
                (id, tenant_id, batch_id, payee_merchant_id, external_txn_id,
                 amount_paise, txn_type, payment_rail, payee_category,
                 charged_mdr_paise, occurred_at, recorded_at, raw)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """;

    private final JdbcTemplate jdbc;
    private final SettlementCsvParser parser;

    public IngestionService(JdbcTemplate jdbc, SettlementCsvParser parser) {
        this.jdbc = jdbc;
        this.parser = parser;
    }

    public IngestionResult ingest(UUID tenantId, Path file) throws IOException {
        long t0 = System.currentTimeMillis();

        parser.verifyHeader(file);
        String hash = sha256(file);

        // Idempotency, checked before doing any work.
        List<UUID> existing = jdbc.query(
                "SELECT id FROM ingestion_batch WHERE tenant_id = ? AND content_hash = ?",
                (rs, i) -> rs.getObject(1, UUID.class), tenantId, hash);
        if (!existing.isEmpty()) {
            log.info("File already ingested (hash {}), skipping", hash.substring(0, 12));
            return new IngestionResult(existing.getFirst(), 0, true,
                    System.currentTimeMillis() - t0);
        }

        UUID batchId = UUID.randomUUID();
        Instant recordedAt = Instant.now();   // one value for the whole file

        jdbc.update("""
                INSERT INTO ingestion_batch
                    (id, tenant_id, source_type, source_name, content_hash, status, received_at)
                VALUES (?, ?, 'PSP_STATEMENT', ?, ?, 'PARSING', ?)
                """, batchId, tenantId, file.getFileName().toString(), hash,
                Timestamp.from(recordedAt));

        Map<String, UUID> merchantCache = new HashMap<>();
        List<Object[]> buffer = new ArrayList<>(BATCH_SIZE);
        long rows = 0;

        try (Stream<SettlementRow> stream = parser.parse(file)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                SettlementRow r = it.next();
                buffer.add(toParams(tenantId, batchId, recordedAt,
                        merchantId(tenantId, r.merchantRef(), merchantCache), r));

                if (buffer.size() >= BATCH_SIZE) {
                    jdbc.batchUpdate(INSERT_EVENT, buffer);
                    rows += buffer.size();
                    buffer.clear();               // the only thing that grows, and it is bounded
                }
            }
        }
        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(INSERT_EVENT, buffer);
            rows += buffer.size();
        }

        jdbc.update("UPDATE ingestion_batch SET status = 'PARSED', row_count = ? WHERE id = ?",
                rows, batchId);

        long ms = System.currentTimeMillis() - t0;
        log.info("Ingested {} rows from {} in {} ms ({} rows/sec)",
                rows, file.getFileName(), ms, ms == 0 ? rows : rows * 1000 / ms);
        return new IngestionResult(batchId, rows, false, ms);
    }

    private Object[] toParams(UUID tenantId, UUID batchId, Instant recordedAt,
                              UUID merchantId, SettlementRow r) {
        return new Object[]{
                UUID.randomUUID(), tenantId, batchId, merchantId, r.externalTxnId(),
                r.amountPaise(), r.txnType().name(), r.rail().name(), r.payeeCategory().name(),
                r.chargedMdrPaise(), Timestamp.from(r.occurredAt()), Timestamp.from(recordedAt),
                rawJson(r.rawLine())
        };
    }

    /**
     * Resolves a merchant reference to its id, creating the merchant on first
     * sight. Cached per file: our sample has 2,000,000 rows but only 500
     * distinct merchants, so this turns 2,000,000 queries into 500.
     */
    private UUID merchantId(UUID tenantId, String ref, Map<String, UUID> cache) {
        return cache.computeIfAbsent(ref, r -> {
            List<UUID> found = jdbc.query(
                    "SELECT id FROM merchant WHERE tenant_id = ? AND external_ref = ?",
                    (rs, i) -> rs.getObject(1, UUID.class), tenantId, r);
            if (!found.isEmpty()) return found.getFirst();

            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO merchant (id, tenant_id, external_ref, display_name)
                    VALUES (?, ?, ?, ?)
                    """, id, tenantId, r, r);
            return id;
        });
    }

    /** Hashes the file as a stream: the bytes are never all in memory at once. */
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String rawJson(String line) {
        StringBuilder sb = new StringBuilder(line.length() + 16).append("{\"line\":\"");
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append("\"}").toString();
    }
}
