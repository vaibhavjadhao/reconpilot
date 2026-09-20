package in.reconpilot.dispute;

import in.reconpilot.AbstractIntegrationTest;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Shared fixture builder. Holds no tests of its own, so the two dispute test
 * classes can share it without inheriting each other's cases -- they run under
 * different configuration and must not.
 */
abstract class DisputeTestSupport extends AbstractIntegrationTest {

    private UUID tenant;

    /**
     * Builds the minimum chain of rows a break needs to exist.
     *
     * <p>All breaks share one tenant. Creating a fresh tenant per break used to
     * be harmless; with row-level security enforced it means a test can only
     * ever see the last one it made, which is correct behaviour and a useless
     * fixture.
     */
    protected UUID aBreakWithDelta(long deltaPaise, String breakType) {
        if (tenant == null) tenant = newTenant("acme-" + UUID.randomUUID());
        in.reconpilot.security.TenantContext.set(tenant);
        UUID merchant = UUID.randomUUID(), batch = UUID.randomUUID(),
             txn = UUID.randomUUID(), brk = UUID.randomUUID();

        jdbc.update("INSERT INTO merchant (id, tenant_id, external_ref, display_name) VALUES (?,?,?,?)",
                merchant, tenant, "shop-" + merchant + "@upi", "shop");
        jdbc.update("""
                INSERT INTO ingestion_batch (id, tenant_id, source_type, source_name, content_hash, status)
                VALUES (?,?,'PSP_STATEMENT','t.csv',?, 'PARSED')
                """, batch, tenant, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO transaction_event
                    (id, tenant_id, batch_id, payee_merchant_id, external_txn_id, amount_paise,
                     txn_type, payment_rail, payee_category, charged_mdr_paise, occurred_at, raw)
                VALUES (?,?,?,?,?,?, 'P2M','UPI_QR','STANDARD', ?, ?, '{}'::jsonb)
                """, txn, tenant, batch, merchant, "T1", 300_000L, 1_200 + deltaPaise,
                Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO recon_break
                    (id, tenant_id, transaction_event_id, expected_mdr_paise, charged_mdr_paise,
                     delta_paise, break_type, status, ruleset_version)
                VALUES (?,?,?,?,?,?,?, 'OPEN', 'test')
                """, brk, tenant, txn, 1_200L, 1_200 + deltaPaise, deltaPaise, breakType);
        return brk;
    }
}
