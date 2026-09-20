package in.reconpilot.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes work onto Kafka, and waits for the broker to acknowledge it.
 *
 * <p>The wait matters. {@code KafkaTemplate.send} returns a future
 * immediately, so without joining it the HTTP request would report 202 while
 * the publish was still in flight -- and a broker rejection would surface as a
 * log line nobody reads, with the caller told the work was accepted. Blocking
 * for the acknowledgement makes acceptance mean something.
 *
 * <p>The key is the tenant id, so Kafka places all of one tenant's messages in
 * the same partition and therefore processes them in order.
 */
@Component
public class IngestionPublisher {

    private static final Logger log = LoggerFactory.getLogger(IngestionPublisher.class);
    private static final long ACK_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, Object> kafka;

    public IngestionPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    public void publishIngestion(IngestionRequested event) {
        send(Topics.INGESTION_REQUESTED, event.tenantId(), event, event.batchId());
    }

    public void publishReconciliation(ReconciliationRequested event) {
        send(Topics.RECON_REQUESTED, event.tenantId(), event, event.batchId());
    }

    private void send(String topic, UUID key, Object payload, UUID batchId) {
        try {
            var result = kafka.send(topic, key.toString(), payload)
                              .get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var meta = result.getRecordMetadata();
            log.info("Published {} for batch {} to {}-{} offset {}",
                    payload.getClass().getSimpleName(), batchId,
                    meta.topic(), meta.partition(), meta.offset());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Interrupted publishing to " + topic, e);
        } catch (Exception e) {
            throw new MessagePublishException("Could not publish to " + topic, e);
        }
    }
}
