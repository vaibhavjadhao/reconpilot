package in.reconpilot.messaging;

import in.reconpilot.ingest.IngestionWorker;
import in.reconpilot.recon.ReconciliationService;
import in.reconpilot.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Where the queued work actually runs.
 *
 * <p>Replaces the in-process thread pool. The pool was bounded and rejected
 * work when full, which was honest but meant an accepted upload existed only in
 * this JVM's memory: a restart lost it (defect D5). A broker holds the message
 * until a consumer acknowledges it, so a process killed mid-batch simply has
 * the message redelivered.
 *
 * <p><b>Delivery is at-least-once, so a message can arrive twice</b> -- for
 * instance if the process dies after finishing the work but before committing
 * the offset. That is safe here only because the consumer is idempotent:
 * ingestion keys on the file's SHA-256 under a unique constraint, and
 * reconciliation on one break per transaction. Those constraints were added
 * long before Kafka existed in this project, which is why adopting it needed no
 * change to either.
 */
@Component
public class IngestionListener {

    private static final Logger log = LoggerFactory.getLogger(IngestionListener.class);

    private final IngestionWorker worker;
    private final ReconciliationService recon;

    public IngestionListener(IngestionWorker worker, ReconciliationService recon) {
        this.worker = worker;
        this.recon = recon;
    }

    /**
     * Concurrency is capped at 3 because the topic has 3 partitions -- a fourth
     * consumer in the group would be assigned nothing and sit idle.
     */
    @KafkaListener(
            topics = Topics.INGESTION_REQUESTED,
            groupId = "reconpilot-ingestion",
            concurrency = "3")
    public void onIngestionRequested(IngestionRequested event) {
        log.info("Consuming ingestion request for batch {}", event.batchId());
        worker.process(event.batchId(), event.tenantId(),
                Path.of(event.stagedPath()), event.recordedAt());
    }

    @KafkaListener(
            topics = Topics.RECON_REQUESTED,
            groupId = "reconpilot-reconciliation",
            concurrency = "3")
    public void onReconciliationRequested(ReconciliationRequested event) {
        log.info("Consuming reconciliation request for batch {}", event.batchId());
        // The listener thread carries no tenant, and every row-level security
        // policy depends on one. Set it, and clear it: listener threads are
        // pooled and reused, so a tenant left behind becomes the next
        // message's tenant.
        TenantContext.set(event.tenantId());
        try {
            recon.reconcile(event.batchId());
        } finally {
            TenantContext.clear();
        }
    }
}
