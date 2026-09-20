package in.reconpilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Deals with the one kind of work a restart cannot recover on its own.
 *
 * <h2>What changed when Kafka arrived</h2>
 *
 * This used to mark every RECEIVED or PARSING batch as FAILED at startup,
 * because their state lived in a thread pool that died with the process. That
 * is now wrong for PARSING: the broker still holds the message, redelivers it,
 * and the batch finishes. Marking it FAILED would contradict what is about to
 * happen.
 *
 * <h2>The gap that remains</h2>
 *
 * Accepting an upload does two things that are not atomic: it inserts a batch
 * row, then publishes to Kafka. A process killed between them leaves a RECEIVED
 * row with no message, and nothing will ever process it. That is the
 * <b>dual-write problem</b> -- two systems, one logical operation, no shared
 * transaction -- and it has no solution using only a database and a broker.
 *
 * <p>The real fix is the transactional outbox pattern: write the message into
 * an outbox table in the same transaction as the batch row, and have a separate
 * process publish from that table. Then the only atomic act is a database
 * commit. That is not built here; see defect D13.
 *
 * <p>Until then, this sweeps up the orphans so they are visible rather than
 * silently stuck. RECEIVED batches older than the grace period cannot be
 * in-flight, because a published message is consumed within seconds.
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    /** Comfortably longer than a consumer-group rebalance. */
    private static final String GRACE = "5 minutes";

    private final JdbcTemplate jdbc;

    public StartupRecovery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int stranded = jdbc.update("""
                UPDATE ingestion_batch
                   SET status='FAILED',
                       completed_at=?,
                       error_message='Accepted but never queued: the process stopped between '
                                   || 'recording the batch and publishing it. Re-upload the file.'
                 WHERE status = 'RECEIVED'
                   AND received_at < now() - INTERVAL '%s'
                """.formatted(GRACE), Timestamp.from(Instant.now()));

        if (stranded > 0) {
            log.warn("Marked {} batch(es) FAILED: accepted but never published (dual-write gap, D13)",
                    stranded);
        }
    }
}
