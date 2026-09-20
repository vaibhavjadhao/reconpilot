package in.reconpilot.ingest;

import in.reconpilot.security.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
public class IngestionController {

    private final IngestionCoordinator coordinator;
    private final FileStagingService staging;
    private final JdbcTemplate jdbc;

    public IngestionController(IngestionCoordinator coordinator,
                               FileStagingService staging,
                               JdbcTemplate jdbc) {
        this.coordinator = coordinator;
        this.staging = staging;
        this.jdbc = jdbc;
    }

    /**
     * Accepts an uploaded settlement file and returns immediately.
     *
     * <p>Replaces the earlier endpoint that took a server-side path, which was
     * defect D4: it would read any file the process could read, so a caller
     * could ask for {@code /etc/passwd} or the application's own configuration.
     * An upload can only ever supply its own bytes.
     *
     * <p>202 Accepted rather than 200: the request was valid and the work is
     * scheduled, but it is not done. The Location header points at where the
     * outcome will appear.
     */
    @PostMapping(value = "/api/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestionSubmission> upload(@RequestPart("file") MultipartFile file)
            throws IOException {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("No file was uploaded.");
        }

        // Streamed to disk and hashed in one pass; never held in memory.
        StagedFile staged = staging.stage(file.getInputStream(), file.getOriginalFilename());

        IngestionSubmission s;
        try {
            s = coordinator.submit(TenantContext.get(), staged, file.getOriginalFilename());
        } catch (RuntimeException | IOException e) {
            // Nothing will process this file now, so it must not be left behind.
            staging.discard(staged.path());
            throw e;
        }

        if (s.alreadySeen()) {
            // A duplicate queues no work, so no worker will ever clean it up.
            staging.discard(staged.path());
            return ResponseEntity.ok()
                    .location(URI.create("/api/ingest/" + s.batchId())).body(s);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/ingest/" + s.batchId())).body(s);
    }

    /** Where the client finds out how it went. */
    @GetMapping("/api/ingest/{batchId}")
    public BatchStatus status(@PathVariable UUID batchId) {
        List<BatchStatus> found = jdbc.query(SELECT_BATCH + " WHERE id = ?", MAP_BATCH, batchId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("No such batch: " + batchId);
        }
        return found.getFirst();
    }

    @GetMapping("/api/ingest")
    public List<BatchStatus> recent() {
        return jdbc.query(SELECT_BATCH + " ORDER BY received_at DESC LIMIT 20", MAP_BATCH);
    }

    private static final String SELECT_BATCH = """
            SELECT id, source_name, status, row_count,
                   received_at, started_at, completed_at, error_message
              FROM ingestion_batch
            """;

    private static final org.springframework.jdbc.core.RowMapper<BatchStatus> MAP_BATCH =
            (rs, i) -> new BatchStatus(
                    rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4) == null ? null : rs.getLong(4),
                    instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)),
                    instant(rs.getTimestamp(7)), rs.getString(8));

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
