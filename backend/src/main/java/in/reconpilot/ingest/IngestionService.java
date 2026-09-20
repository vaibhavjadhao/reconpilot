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

    /**
     * The synchronous part: cheap checks that the caller must hear about
     * immediately, done before any long work is queued.
     *
     * <p>Hashing the file is included here deliberately. It costs roughly half
     * a second for 148 MB, and doing it up front means a duplicate upload is
     * rejected instantly rather than after being queued. For a 2 GB file this
     * would be several seconds -- a tradeoff worth revisiting if it bites.
     */
    public PreparedBatch prepare(UUID tenantId, Path file) throws IOException {
        // Reads the file a second time to hash it. Kept for callers that have a
        // plain path; the upload route uses the overload below, which reuses the
        // digest computed while the bytes were being written to disk.
        return prepare(tenantId, file, sha256(file), file.getFileName().toString());
    }

    /**
     * @param hash       SHA-256 computed by the caller, typically while streaming
     *                   the upload to disk, so the bytes are read once
     * @param sourceName the name to show a user, which is the original upload
     *                   name rather than the randomised staging filename
     */
    public PreparedBatch prepare(UUID tenantId, Path file, String hash, String sourceName)
            throws IOException {
        parser.verifyHeader(file);

        // FAILED batches are deliberately excluded: a file that failed must be
        // retryable, otherwise one transient error blocks it forever. PARSING
        // and RECEIVED do block, because that work is genuinely in flight.
        List<UUID> existing = jdbc.query("""
                SELECT id FROM ingestion_batch
                 WHERE tenant_id = ? AND content_hash = ? AND status <> 'FAILED'
                """, (rs, i) -> rs.getObject(1, UUID.class), tenantId, hash);
        if (!existing.isEmpty()) {
            log.info("File already ingested (hash {}), skipping", hash.substring(0, 12));
            return new PreparedBatch(existing.getFirst(), null, true);
        }

        UUID batchId = UUID.randomUUID();
        Instant recordedAt = Instant.now();

        jdbc.update("""
                INSERT INTO ingestion_batch
                    (id, tenant_id, source_type, source_name, content_hash, status, received_at)
                VALUES (?, ?, 'PSP_STATEMENT', ?, ?, 'RECEIVED', ?)
                """, batchId, tenantId, sourceName, hash,
                Timestamp.from(recordedAt));

        return new PreparedBatch(batchId, recordedAt, false);
    }

    /**
     * The long part: streams the file and writes rows. Called from a worker
     * thread, never from an HTTP thread.
     */
    public long loadRows(UUID tenantId, UUID batchId, Path file, Instant recordedAt)
            throws IOException {

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
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            jdbc.batchUpdate(INSERT_EVENT, buffer);
            rows += buffer.size();
        }
        return rows;
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
     * Resolves a merchant reference to its id, creating it on first sight.
     *
     * <p>Cached per file: 2,000,000 rows but only 500 distinct merchants, so
     * this turns 2,000,000 lookups into 500.
     *
     * <p>The insert is an atomic upsert rather than a check-then-act, because
     * several files ingest concurrently and may mention the same merchant. The
     * naive form -- SELECT, then INSERT if absent -- has a window between the
     * two statements in which another thread can insert the same merchant,
     * and the second INSERT then dies on the unique constraint, failing the
     * whole batch. That bug is invisible under sequential ingestion and appears
     * immediately under concurrency.
     *
     * <p>ON CONFLICT lets PostgreSQL resolve the race atomically. The
     * apparently pointless {@code DO UPDATE SET display_name = merchant.display_name}
     * is deliberate: {@code DO NOTHING} returns no rows, so RETURNING would
     * yield nothing on conflict. Assigning the column to itself makes the
     * conflicting row be returned.
     */
    private UUID merchantId(UUID tenantId, String ref, Map<String, UUID> cache) {
        return cache.computeIfAbsent(ref, r -> jdbc.queryForObject("""
                INSERT INTO merchant (id, tenant_id, external_ref, display_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (tenant_id, external_ref)
                DO UPDATE SET display_name = merchant.display_name
                RETURNING id
                """, (rs, i) -> rs.getObject(1, UUID.class),
                UUID.randomUUID(), tenantId, r, r));
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
