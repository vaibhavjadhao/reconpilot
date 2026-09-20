package in.reconpilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies the whole application context starts: every bean resolves, Flyway
 * migrations apply cleanly, and Hibernate's {@code validate} agrees that the
 * entities match the schema Flyway built.
 *
 * <p>Named {@code *IT} rather than {@code *Tests} deliberately. Spring
 * Initializr generates this as {@code BackendApplicationTests}, which puts it
 * in Surefire's fast phase -- but it starts a database container, so
 * {@code mvn test} then required Docker to pass.
 *
 * <p>That is a category error worth naming: <b>a test that needs a database is
 * not a unit test.</b> Misclassifying it makes the fast feedback loop slow and
 * fragile, and means a developer without Docker running sees failures that have
 * nothing to do with their change.
 *
 * <p>With this moved, {@code mvn test} runs 34 genuinely isolated tests in
 * seconds with no Docker at all, and {@code mvn verify} runs everything.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationContextIT {

    @Test
    void contextLoads() {
    }
}
