package in.reconpilot.ingest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class IngestionConfig {

    /**
     * The pool that runs ingestion.
     *
     * <p>Every bound here is deliberate. An unbounded pool creates a thread per
     * submission until the JVM dies; an unbounded queue accepts work forever
     * and fails as an OutOfMemoryError hours later, far from the cause.
     *
     * <p>Note how {@link ThreadPoolTaskExecutor} actually grows, because it
     * surprises people: it creates threads up to corePoolSize, then <em>fills
     * the queue</em>, and only once the queue is full does it grow towards
     * maxPoolSize. So maxPoolSize is not reached until 2 + 10 tasks are
     * outstanding.
     *
     * <p>AbortPolicy is chosen over CallerRunsPolicy on purpose. CallerRuns
     * would hand the work back to the HTTP thread -- silently restoring the
     * synchronous behaviour we are removing, at the worst possible moment.
     * Aborting surfaces overload honestly as a 503 and lets the client retry.
     */
    @Bean("ingestionExecutor")
    public ThreadPoolTaskExecutor ingestionExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("ingest-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }
}
