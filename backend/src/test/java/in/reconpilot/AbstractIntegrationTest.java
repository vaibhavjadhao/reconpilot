package in.reconpilot;

import in.reconpilot.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Base for tests that need a real PostgreSQL.
 *
 * <p>Sharing one base class keeps Spring's context cache working: the container
 * starts once for the whole suite rather than once per test class.
 *
 * <p>Two details that changed when row-level security was enforced:
 *
 * <ul>
 *   <li>Fixtures use the <b>admin</b> template. TRUNCATE requires table
 *       ownership, and seeding several tenants is by definition cross-tenant
 *       work. Test setup is a system operation, not a request.
 *   <li>{@link #newTenant} also establishes the tenant context, because any
 *       service the test then calls runs on the tenant-scoped connection and
 *       would otherwise correctly see nothing.
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    /** Owner connection: for fixtures and assertions that must span tenants. */
    @Autowired
    @Qualifier("adminJdbcTemplate")
    protected JdbcTemplate jdbc;

    @Autowired
    protected org.testcontainers.postgresql.PostgreSQLContainer postgres;

    /**
     * Refuses to touch anything that is not a throwaway container.
     *
     * <p>Not paranoia. A misconfigured admin datasource once pointed this
     * suite's TRUNCATE at the local development database and destroyed a
     * million rows, and nothing warned because from Spring's point of view the
     * configuration was valid. A destructive operation should verify what it is
     * about to destroy, not trust that the wiring is right.
     */
    @BeforeEach
    void refuseToRunAgainstAnythingButAContainer() {
        String url = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                c -> c.getMetaData().getURL());
        if (!url.equals(postgres.getJdbcUrl())) {
            throw new IllegalStateException(
                    "Refusing to run: the admin connection is not the test container.%n"
                  + "  expected: %s%n  actual:   %s".formatted(postgres.getJdbcUrl(), url));
        }
    }

    @BeforeEach
    void clearDatabase() {
        TenantContext.clear();
        jdbc.execute("""
                TRUNCATE transaction_event, recon_break, dispute, dispute_event,
                         ingestion_batch, merchant, app_user, tenant CASCADE
                """);
    }

    @AfterEach
    void clearTenant() {
        // Test threads are reused across classes; a tenant left behind would
        // silently scope the next test.
        TenantContext.clear();
    }

    /** Creates a tenant and makes it the current one for this thread. */
    protected UUID newTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", id, name);
        TenantContext.set(id);
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
