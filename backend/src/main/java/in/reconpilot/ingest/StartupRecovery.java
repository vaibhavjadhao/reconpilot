package in.reconpilot.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Deals with work that was in flight when the process last died.
 *
 * <p>Ingestion state lives in a thread pool inside this JVM. If the process is
 * killed mid-file -- a deploy, a crash, an OOM -- those threads vanish and any
 * batch left in PARSING will stay there forever, because nothing remembers it
 * was running.
 *
 * <p>This marks such batches FAILED at startup so they are visible and can be
 * retried, rather than silently stuck. It is a mitigation, not a fix: the real
 * answer is a durable queue outside the process, which is the next step.
 */
@Component
public class StartupRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final JdbcTemplate jdbc;

    public StartupRecovery(@Qualifier("adminJdbcTemplate") JdbcTemplate jdbc) {
        // The owner connection, deliberately. This sweep spans every tenant
        // and runs at startup, when there is no logged-in user and therefore
        // no tenant context -- on the application connection, row-level
        // security would correctly hide every row and the sweep would silently
        // do nothing.
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int stranded = jdbc.update("""
                UPDATE ingestion_batch
                   SET status='FAILED',
                       completed_at=?,
                       error_message='Interrupted by process restart; in-process state was lost'
                 WHERE status IN ('RECEIVED','PARSING')
                """, Timestamp.from(Instant.now()));

        if (stranded > 0) {
            log.warn("Marked {} batch(es) FAILED: they were in flight when the process last stopped",
                    stranded);
        }
    }
}
