package in.reconpilot;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Base for tests that need a real PostgreSQL.
 *
 * <p>Sharing one base class keeps Spring's context cache working: the container
 * starts once for the whole suite rather than once per test class. Varying the
 * annotations between classes would create a second context and a second
 * container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void clearDatabase() {
        jdbc.execute("TRUNCATE transaction_event, recon_break, ingestion_batch, merchant, tenant CASCADE");
    }

    protected UUID newTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", id, name);
        return id;
    }

    protected long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    protected String batchStatus(UUID batchId) {
        List<String> s = jdbc.queryForList(
                "SELECT status FROM ingestion_batch WHERE id = ?", String.class, batchId);
        return s.isEmpty() ? null : s.getFirst();
    }
}
