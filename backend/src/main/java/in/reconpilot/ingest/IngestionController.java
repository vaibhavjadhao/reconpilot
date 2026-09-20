package in.reconpilot.ingest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import in.reconpilot.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Development-only ingestion trigger.
 *
 * <p>It accepts a server-side path, which is fine on a laptop and unacceptable
 * in production: a caller could name any file the process can read. Tracked as
 * D4 in KNOWN-DEFECTS.md. The production route is a streamed multipart upload
 * or an object-store key.
 */
@RestController
public class IngestionController {

    private final IngestionCoordinator coordinator;
    private final JdbcTemplate jdbc;

    public IngestionController(IngestionCoordinator coordinator, JdbcTemplate jdbc) {
        this.coordinator = coordinator;
        this.jdbc = jdbc;
    }

    /**
     * Accepts the file and returns immediately.
     *
     * <p>202 Accepted is the correct status: the request was valid and the work
     * is scheduled, but it is not done. 200 would be a lie -- it claims a
     * completed result the caller does not have. The Location header points at
     * where the outcome will appear.
     */
    @PostMapping("/api/ingest")
    public ResponseEntity<IngestionSubmission> ingest(@RequestParam String path) throws IOException {

        Path file = Path.of(path);
        if (!Files.isReadable(file)) {
            throw new IllegalArgumentException("Not readable: " + path);
        }

        // The tenant comes from the signed token, never from a parameter the
        // caller controls. A ?tenant= parameter would let anyone write into
        // anyone's data by editing a URL.
        IngestionSubmission s = coordinator.submit(TenantContext.get(), file);

        HttpStatus status = s.alreadySeen() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/ingest/" + s.batchId()))
                .body(s);
    }

    /** Where the client finds out how it went. */
    @GetMapping("/api/ingest/{batchId}")
    public BatchStatus status(@PathVariable UUID batchId) {
        List<BatchStatus> found = jdbc.query("""
                SELECT id, source_name, status, row_count,
                       received_at, started_at, completed_at, error_message
                  FROM ingestion_batch WHERE id = ?
                """, (rs, i) -> new BatchStatus(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4),
                        instant(rs.getTimestamp(5)),
                        instant(rs.getTimestamp(6)),
                        instant(rs.getTimestamp(7)),
                        rs.getString(8)), batchId);

        if (found.isEmpty()) {
            throw new IllegalArgumentException("No such batch: " + batchId);
        }
        return found.getFirst();
    }

    @GetMapping("/api/ingest")
    public List<BatchStatus> recent() {
        return jdbc.query("""
                SELECT id, source_name, status, row_count,
                       received_at, started_at, completed_at, error_message
                  FROM ingestion_batch ORDER BY received_at DESC LIMIT 20
                """, (rs, i) -> new BatchStatus(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4) == null ? null : rs.getLong(4),
                        instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)),
                        instant(rs.getTimestamp(7)), rs.getString(8)));
    }

    private static java.time.Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

}
