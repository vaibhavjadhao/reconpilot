package in.reconpilot.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /**
     * What happens when a listener throws.
     *
     * <p>Without an error handler, Spring retries the same record indefinitely:
     * the consumer never advances its offset, the partition stops, and every
     * later message behind it waits forever. One badly formed file would halt
     * ingestion for every tenant sharing that partition. That failure mode is
     * called a poison message, and it is the single most common way a Kafka
     * consumer falls over in production.
     *
     * <p>So: three attempts two seconds apart, then publish the record to a
     * dead-letter topic and move on. The bad message is kept for inspection
     * rather than dropped, and the partition keeps flowing.
     *
     * <p>Two seconds is deliberately short. These failures are usually a
     * malformed file or a missing staged file -- neither improves with waiting.
     * A retry policy should match why the thing might succeed next time.
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.error("Sending to dead-letter topic after retries: topic={} partition={} offset={} key={}",
                            record.topic(), record.partition(), record.offset(), record.key(), ex);
                    recoverer.accept((ConsumerRecord<?, ?>) record, ex);
                },
                new FixedBackOff(2_000L, 2L));

        // A malformed payload will never succeed, however many times it is
        // retried, so it goes straight to the dead-letter topic.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                org.springframework.kafka.support.serializer.DeserializationException.class);

        return handler;
    }
}
