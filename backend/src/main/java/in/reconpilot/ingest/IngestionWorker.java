package in.reconpilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import in.reconpilot.security.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Runs the long part of ingestion off the request thread.
 *
 * <p>This lives in its own bean rather than as a method on
 * {@link IngestionService} for a reason worth understanding: {@code @Async} is
 * implemented with a proxy. Spring wraps the bean, and the wrapper is what
 * dispatches to another thread. A call from one method of a bean to another
 * method of the <em>same</em> bean goes through {@code this}, not the proxy, so
 * the annotation is silently ignored and the code runs synchronously.
 *
 * <p>It is a common and genuinely confusing bug: the annotation is present, the
 * code compiles, and nothing is async. Calling across beans avoids it entirely.
 * The same trap applies to {@code @Transactional} and {@code @Cacheable}.
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);

    private final JdbcTemplate jdbc;
    private final IngestionService service;

    public IngestionWorker(JdbcTemplate jdbc, IngestionService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @Async("ingestionExecutor")
    public void process(UUID batchId, UUID tenantId, Path file, Instant recordedAt) {
        log.info("[{}] starting ingestion of {} on thread {}",
                batchId, file.getFileName(), Thread.currentThread().getName());

        // A ThreadLocal does not cross a thread boundary. The request thread
        // that accepted the upload has the tenant; this pool thread does not,
        // and without it every row-level security policy would match nothing
        // and the ingestion would silently write and read zero rows.
        //
        // The tenant is passed explicitly as a parameter for exactly this
        // reason, and re-established here for the life of the task.
        TenantContext.set(tenantId);

        jdbc.update("UPDATE ingestion_batch SET status='PARSING', started_at=? WHERE id=?",
                Timestamp.from(Instant.now()), batchId);

        try {
            long rows = service.loadRows(tenantId, batchId, file, recordedAt);

            jdbc.update("""
                    UPDATE ingestion_batch
                       SET status='PARSED', row_count=?, completed_at=?
                     WHERE id=?
                    """, rows, Timestamp.from(Instant.now()), batchId);

            log.info("[{}] completed: {} rows", batchId, rows);

        } catch (Exception e) {
            // Nobody is holding a connection to receive this, so it must be
            // recorded where the client can poll for it.
            log.error("[{}] ingestion failed", batchId, e);
            jdbc.update("""
                    UPDATE ingestion_batch
                       SET status='FAILED', completed_at=?, error_message=?
                     WHERE id=?
                    """, Timestamp.from(Instant.now()),
                    e.getClass().getSimpleName() + ": " + e.getMessage(), batchId);
        } finally {
            // Pool threads are reused, so a tenant left behind becomes the
            // next ingestion's tenant.
            TenantContext.clear();
        }
    }
}
