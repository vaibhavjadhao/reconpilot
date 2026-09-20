package in.reconpilot.ingest;

import in.reconpilot.messaging.IngestionPublisher;
import in.reconpilot.messaging.IngestionRequested;
import in.reconpilot.messaging.MessagePublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Accepts a file and hands the work to Kafka.
 *
 * <p>The synchronous part is everything the caller must hear about
 * immediately: the header is valid, the file is not a duplicate, and a batch
 * row exists to poll. Everything after that happens in a consumer.
 */
@Service
public class IngestionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(IngestionCoordinator.class);

    private final IngestionService service;
    private final IngestionPublisher publisher;
    private final JdbcTemplate jdbc;

    public IngestionCoordinator(IngestionService service, IngestionPublisher publisher, JdbcTemplate jdbc) {
        this.service = service;
        this.publisher = publisher;
        this.jdbc = jdbc;
    }

    /** Used by tests and by any caller that already has a file on disk. */
    public IngestionSubmission submit(UUID tenantId, Path file) throws IOException {
        PreparedBatch prepared = service.prepare(tenantId, file);
        return dispatch(tenantId, file, file.getFileName().toString(), prepared);
    }

    /** Upload route: the digest is already known from staging. */
    public IngestionSubmission submit(UUID tenantId, StagedFile staged, String originalName)
            throws IOException {
        PreparedBatch prepared =
                service.prepare(tenantId, staged.path(), staged.sha256(), originalName);
        return dispatch(tenantId, staged.path(), originalName, prepared);
    }

    private IngestionSubmission dispatch(UUID tenantId, Path file, String originalName,
                                         PreparedBatch prepared) {
        if (prepared.alreadySeen()) {
            return new IngestionSubmission(prepared.batchId(), "PARSED", true);
        }

        try {
            publisher.publishIngestion(new IngestionRequested(
                    prepared.batchId(), tenantId, file.toAbsolutePath().toString(),
                    originalName, prepared.recordedAt()));

        } catch (MessagePublishException e) {
            // The batch row is written before the message is published, so a
            // broker failure would leave it stranded in RECEIVED. Worse, the
            // idempotency check keys on content_hash, so that stranded row
            // would make every retry of this file report "already ingested" --
            // turning an honest failure into silent, permanent data loss.
            //
            // Removing the row restores the state to exactly as it was before
            // the request, which is what a rejected request should leave
            // behind. Previously this guarded against a full thread pool; the
            // mechanism changed, the invariant did not.
            jdbc.update("DELETE FROM ingestion_batch WHERE id = ?", prepared.batchId());
            log.warn("Publish failed; removed batch {} so the file can be retried",
                    prepared.batchId());
            throw e;
        }

        return new IngestionSubmission(prepared.batchId(), "RECEIVED", false);
    }
}
