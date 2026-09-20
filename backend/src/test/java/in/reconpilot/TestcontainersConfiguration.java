package in.reconpilot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Pinned to the same image docker-compose.yml runs.
     *
     * <p>The generated default was {@code postgres:latest}, which meant tests
     * ran against whatever version happened to be current -- 18.6 at the time,
     * while production ran 17. Testing against a different database version
     * than you deploy is a quiet way to be surprised: behaviour around
     * collation, planner choices and defaults does change between majors.
     *
     * <p>It also makes the suite non-reproducible, since the same commit tests
     * differently next month.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }
}
