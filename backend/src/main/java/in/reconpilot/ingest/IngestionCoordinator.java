package in.reconpilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * Ties the synchronous check to the asynchronous work.
 *
 * <p>Exists as its own bean so {@link IngestionService} need not know about
 * {@link IngestionWorker}, which already depends on it -- otherwise the two
 * would form a cycle.
 */
@Service
public class IngestionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IngestionCoordinator.class);

    private final IngestionService service;
    private final IngestionWorker worker;
    private final JdbcTemplate jdbc;

    public IngestionCoordinator(IngestionService service, IngestionWorker worker, JdbcTemplate jdbc) {
        this.service = service;
        this.worker = worker;
        this.jdbc = jdbc;
    }

    public IngestionSubmission submit(UUID tenantId, Path file) throws IOException {
        return submit(tenantId, file, service.prepare(tenantId, file));
    }

    /** Upload route: the digest is already known from staging. */
    public IngestionSubmission submit(UUID tenantId, StagedFile staged, String originalName)
            throws IOException {
        return submit(tenantId, staged.path(),
                service.prepare(tenantId, staged.path(), staged.sha256(), originalName));
    }

    private IngestionSubmission submit(UUID tenantId, Path file, PreparedBatch prepared) {

        if (prepared.alreadySeen()) {
            return new IngestionSubmission(prepared.batchId(), "PARSED", true);
        }

        try {
            // Returns immediately; the work happens on the ingestion pool.
            worker.process(prepared.batchId(), tenantId, file, prepared.recordedAt());

        } catch (RejectedExecutionException e) {
            // The batch row was written before the work was queued, so a
            // rejection here would leave it stranded in RECEIVED. Worse, the
            // idempotency check keys on content_hash, so that stranded row
            // would make every retry of this file report "already ingested" --
            // turning an honest 503 into silent, permanent data loss.
            //
            // Removing the row restores the state to exactly as it was before
            // the request, which is what a rejected request should leave behind.
            jdbc.update("DELETE FROM ingestion_batch WHERE id = ?", prepared.batchId());
            log.warn("Ingestion rejected at capacity; removed batch {} so the file can be retried",
                    prepared.batchId());
            throw e;
        }

        return new IngestionSubmission(prepared.batchId(), "RECEIVED", false);
    }
}
