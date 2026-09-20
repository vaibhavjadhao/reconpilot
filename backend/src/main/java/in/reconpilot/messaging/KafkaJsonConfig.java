package in.reconpilot.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * JSON serialisation for Kafka, configured explicitly.
 *
 * <h2>Why this class exists</h2>
 *
 * Spring Kafka's {@code JsonSerializer} builds its own bare Jackson 2
 * {@code ObjectMapper}. A bare Jackson 2 mapper cannot serialise
 * {@code java.time.Instant} -- it throws rather than guessing a format.
 *
 * <p>This is easy to miss because Spring MVC in this project serialises
 * {@code Instant} happily: the web layer uses Jackson 3, configured by Spring
 * Boot, while Kafka uses Jackson 2, configured by itself. Two libraries, two
 * configurations, one that works and one that does not.
 *
 * <h2>How it presented</h2>
 *
 * The upload endpoint returned <b>401 Unauthorized</b>. The token was valid and
 * the same token worked on every GET. The real failure was this serialisation
 * error inside the publish, thrown after {@code JwtAuthFilter} had already
 * entered its {@code finally} block and cleared the security context -- so by
 * the time the exception reached Spring Security's translation filter there was
 * no authentication, and it answered 401. See defect D12.
 */
@Configuration
public class KafkaJsonConfig {

    /** Dates as ISO-8601 strings, not numeric arrays, so payloads stay readable. */
    private static ObjectMapper kafkaMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    @Bean
    public ProducerFactory<String, Object> producerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildProducerProperties();
        DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(config, new StringSerializer(),
                        new JsonSerializer<>(kafkaMapper()));
        return factory;
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory(KafkaProperties properties) {
        Map<String, Object> config = properties.buildConsumerProperties();

        // The delegate does the real work; the wrapper turns a malformed
        // payload into a record the error handler can route to the
        // dead-letter topic, instead of an exception that stalls the
        // partition forever.
        JsonDeserializer<Object> delegate = new JsonDeserializer<>(kafkaMapper());
        delegate.addTrustedPackages("in.reconpilot.messaging");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(),
                new ErrorHandlingDeserializer<>(delegate));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
