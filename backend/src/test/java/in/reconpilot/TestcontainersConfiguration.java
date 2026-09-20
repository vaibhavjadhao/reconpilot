package in.reconpilot;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * Pinned to the same image docker-compose.yml runs.
     *
     * <p>The generated default was {@code postgres:latest}, which meant tests
     * ran against whatever version happened to be current while production ran
     * 17, and that the same commit would test differently next month.
     */
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
    }

    /**
     * Points the admin and Flyway connections at the container too.
     *
     * <p>{@code @ServiceConnection} only overrides {@code spring.datasource.*}.
     * Any datasource declared under a different property prefix keeps whatever
     * {@code application.properties} says -- which was
     * {@code localhost:5432/reconpilot}, the real development database.
     *
     * <p>The test suite therefore ran {@code TRUNCATE ... CASCADE} against the
     * developer's own data and destroyed a million rows. Nothing warned,
     * because from Spring's point of view everything was configured correctly.
     */
    @Bean
    DynamicPropertyRegistrar containerProperties(PostgreSQLContainer postgres) {
        return registry -> {
            registry.add("app.datasource.admin.url", postgres::getJdbcUrl);
            registry.add("app.datasource.admin.username", postgres::getUsername);
            registry.add("app.datasource.admin.password", postgres::getPassword);
            registry.add("spring.flyway.url", postgres::getJdbcUrl);
            registry.add("spring.flyway.user", postgres::getUsername);
            registry.add("spring.flyway.password", postgres::getPassword);
        };
    }
}
