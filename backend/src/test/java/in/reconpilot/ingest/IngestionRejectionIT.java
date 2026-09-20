package in.reconpilot.ingest;

import in.reconpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * Regression: a rejected submission must not poison its own retry.
 *
 * <p>Its own class rather than a nested one, because {@code @MockitoBean}
 * changes the Spring context and therefore needs its own context-cache entry.
 *
 * <p>The worker is replaced with a stub that always rejects, which is a
 * reliable stand-in for a saturated pool and far more deterministic than
 * trying to genuinely fill one.
 */
class IngestionRejectionIT extends AbstractIntegrationTest {

    @Autowired IngestionCoordinator coordinator;
    @Autowired IngestionService service;
    @MockitoBean IngestionWorker worker;

    @TempDir Path tmp;

    @Test
    void rejectionLeavesNoTraceSoTheFileCanStillBeIngested() throws Exception {
        UUID tenant = newTenant("acme");
        Path p = tmp.resolve("a.csv");
        Files.writeString(p, SettlementCsvParser.EXPECTED_HEADER + "\n"
                + "T1,shop1@upi,300000,P2M,UPI_QR,STANDARD,0,2026-10-15T10:00:00Z\n");

        doThrow(new RejectedExecutionException("pool full"))
                .when(worker).process(any(), any(), any(), any());

        assertThrows(RejectedExecutionException.class, () -> coordinator.submit(tenant, p));

        // The original bug: the batch row survived the rejection, and because
        // idempotency keys on the content hash, every retry then reported
        // "already ingested" -- turning an honest 503 into silent data loss.
        assertEquals(0, count("ingestion_batch"), "a refused request must leave no trace");
        assertFalse(service.prepare(tenant, p).alreadySeen(),
                "the file must remain ingestable after a rejection");
    }
}
