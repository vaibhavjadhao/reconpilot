package in.reconpilot.messaging;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Topics are declared, not auto-created.
 *
 * <p>{@code KAFKA_AUTO_CREATE_TOPICS_ENABLE} is off in docker-compose on
 * purpose. With it on, a typo in a topic name silently creates a new topic and
 * the messages disappear into it -- the producer succeeds, the consumer waits
 * forever, and nothing anywhere reports an error. Declaring them here means a
 * wrong name fails loudly at startup.
 */
@Configuration
public class Topics {

    public static final String INGESTION_REQUESTED = "reconpilot.ingestion.requested";
    public static final String INGESTION_DLT       = "reconpilot.ingestion.requested.DLT";
    public static final String RECON_REQUESTED     = "reconpilot.reconciliation.requested";
    public static final String RECON_DLT           = "reconpilot.reconciliation.requested.DLT";

    /**
     * Three partitions, keyed by tenant.
     *
     * <p>Kafka guarantees ordering only within a partition, so keying by tenant
     * means one tenant's uploads are processed in the order they arrived, while
     * different tenants proceed in parallel. Ordering across tenants is not
     * something anyone needs, and demanding it would force a single partition
     * and therefore a single consumer.
     *
     * <p>Partition count is also the ceiling on useful consumer concurrency:
     * a fourth consumer in the group would simply sit idle.
     */
    @Bean NewTopic ingestionRequested() {
        return TopicBuilder.name(INGESTION_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean NewTopic reconRequested() {
        return TopicBuilder.name(RECON_REQUESTED).partitions(3).replicas(1).build();
    }

    /** Where messages go after retries are exhausted, instead of being dropped. */
    @Bean NewTopic ingestionDlt() {
        return TopicBuilder.name(INGESTION_DLT).partitions(1).replicas(1).build();
    }

    @Bean NewTopic reconDlt() {
        return TopicBuilder.name(RECON_DLT).partitions(1).replicas(1).build();
    }
}
