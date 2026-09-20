package in.reconpilot.security;

import com.zaxxer.hikari.HikariDataSource;
import in.reconpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that row-level security is actually enforced -- the defect D1 was
 * that it was configured and inert.
 *
 * <p>This test cannot use the ordinary test datasource, because Testcontainers
 * connects as the database owner and superuser, and PostgreSQL exempts both
 * from RLS. That exemption is precisely what made the original policies do
 * nothing, so a test running as the owner would pass while proving the
 * opposite of what it claims.
 *
 * <p>It therefore builds its own connection as {@code reconpilot_app}, the
 * least-privilege role migration V6 creates, wrapped in the same
 * {@link TenantAwareDataSource} the application uses.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired PostgreSQLContainer postgres;

    private HikariDataSource raw;
    private JdbcTemplate asApp;
    private UUID tenantA, tenantB;

    @BeforeEach
    void connectAsTheApplicationRole() {
        raw = new HikariDataSource();
        raw.setJdbcUrl(postgres.getJdbcUrl());
        raw.setUsername("reconpilot_app");
        raw.setPassword("localdev_app");
        raw.setMaximumPoolSize(3);
        asApp = new JdbcTemplate(new TenantAwareDataSource(raw));

        tenantA = seedTenant("acme");
        tenantB = seedTenant("globex");
    }

    @AfterEach
    void closePool() {
        TenantContext.clear();
        if (raw != null) raw.close();
    }

    /** Written through the owner connection, so RLS does not interfere. */
    private UUID seedTenant(String name) {
        UUID tenant = UUID.randomUUID(), merchant = UUID.randomUUID(),
             batch = UUID.randomUUID(), txn = UUID.randomUUID();
        jdbc.update("INSERT INTO tenant (id, name) VALUES (?, ?)", tenant, name + UUID.randomUUID());
        TenantContext.clear();   // seeding is admin work; do not adopt a context
        jdbc.update("INSERT INTO merchant (id, tenant_id, external_ref, display_name) VALUES (?,?,?,?)",
                merchant, tenant, name + "@upi", name);
        jdbc.update("""
                INSERT INTO ingestion_batch (id, tenant_id, source_type, source_name, content_hash, status)
                VALUES (?,?,'PSP_STATEMENT',?,?, 'PARSED')
                """, batch, tenant, name + ".csv", UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO transaction_event
                    (id, tenant_id, batch_id, payee_merchant_id, external_txn_id, amount_paise,
                     txn_type, payment_rail, payee_category, charged_mdr_paise, occurred_at, raw)
                VALUES (?,?,?,?,?,?, 'P2M','UPI_QR','STANDARD', ?, ?, '{}'::jsonb)
                """, txn, tenant, batch, merchant, name.toUpperCase() + "-TXN", 300_000L, 1_800L,
                Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO recon_break
                    (id, tenant_id, transaction_event_id, expected_mdr_paise, charged_mdr_paise,
                     delta_paise, break_type, status, ruleset_version)
                VALUES (?,?,?,?,?,?, 'OVERCHARGED','OPEN','test')
                """, UUID.randomUUID(), tenant, txn, 1_200L, 1_800L, 600L);
        return tenant;
    }

    private long visibleBreaks() {
        return jdbc.queryForObject("SELECT count(*) FROM recon_break", Long.class);
    }

    private long visibleBreaksAsApp() {
        return asApp.queryForObject("SELECT count(*) FROM recon_break", Long.class);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void theApplicationRoleIsNeitherOwnerNorSuperuser() {
        // Both exemptions defeated RLS before. If either returns, the policies
        // silently stop applying and every other test here still passes.
        assertFalse(asApp.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user",
                Boolean.class), "the app role must not be a superuser");
        assertNotEquals("reconpilot_app",
                jdbc.queryForObject("SELECT tableowner FROM pg_tables WHERE tablename='recon_break'",
                        String.class),
                "the app role must not own the tables");
    }

    @Test
    void rowLevelSecurityIsForcedOnEveryTenantScopedTable() {
        for (String table : new String[]{"merchant", "ingestion_batch", "transaction_event",
                                         "recon_break", "dispute"}) {
            Boolean forced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            assertTrue(Boolean.TRUE.equals(forced),
                    table + " has RLS enabled but not FORCED, so the owner bypasses it");
        }
    }

    @Test
    void withNoTenantContextNothingIsVisible() {
        TenantContext.clear();
        assertEquals(0, visibleBreaksAsApp(),
                "absence of a tenant must mean no rows, not all rows");
    }

    @Test
    void eachTenantSeesOnlyItsOwnRows() {
        assertEquals(2, visibleBreaks(), "the owner connection sees both, as it should");

        TenantContext.set(tenantA);
        assertEquals(1, visibleBreaksAsApp());
        assertEquals("ACME-TXN", asApp.queryForObject("""
                SELECT t.external_txn_id FROM recon_break b
                  JOIN transaction_event t ON t.id = b.transaction_event_id
                """, String.class));

        TenantContext.set(tenantB);
        assertEquals(1, visibleBreaksAsApp());
        assertEquals("GLOBEX-TXN", asApp.queryForObject("""
                SELECT t.external_txn_id FROM recon_break b
                  JOIN transaction_event t ON t.id = b.transaction_event_id
                """, String.class));
    }

    @Test
    void oneTenantCannotReadAnotherRowEvenKnowingItsId() {
        UUID othersBreak = jdbc.queryForObject(
                "SELECT id FROM recon_break WHERE tenant_id = ?", UUID.class, tenantB);

        TenantContext.set(tenantA);
        assertEquals(0, (long) asApp.queryForObject(
                "SELECT count(*) FROM recon_break WHERE id = ?", Long.class, othersBreak),
                "knowing an id must not grant access to it");
    }

    /**
     * USING governs reads; WITH CHECK governs writes. With USING alone a tenant
     * could insert rows carrying someone else's tenant_id -- writable but
     * invisible, which is worse than readable.
     */
    @Test
    void oneTenantCannotWriteRowsBelongingToAnother() {
        TenantContext.set(tenantA);
        assertThrows(Exception.class, () ->
                asApp.update("INSERT INTO merchant (id, tenant_id, external_ref, display_name) VALUES (?,?,?,?)",
                        UUID.randomUUID(), tenantB, "sneaky@upi", "sneaky"));
    }

    /**
     * Connections are pooled, so a session variable left behind would become
     * the next borrower's tenant. TenantAwareDataSource clears on close and
     * sets on checkout; this checks the result rather than the mechanism.
     */
    @Test
    void aTenantDoesNotLeakOntoTheNextUserOfAPooledConnection() {
        TenantContext.set(tenantA);
        assertEquals(1, visibleBreaksAsApp());

        TenantContext.clear();
        assertEquals(0, visibleBreaksAsApp(),
                "the previous tenant leaked through a recycled connection");

        TenantContext.set(tenantB);
        assertEquals(1, visibleBreaksAsApp());
    }
}
