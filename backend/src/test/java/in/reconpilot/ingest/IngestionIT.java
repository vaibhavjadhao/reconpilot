package in.reconpilot.ingest;

import in.reconpilot.AbstractIntegrationTest;
import in.reconpilot.security.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class IngestionIT extends AbstractIntegrationTest {

    @Autowired IngestionService service;

    @TempDir Path tmp;

    // ---------------------------------------------------------------- helpers

    private Path csv(String name, List<String> rows) throws IOException {
        Path p = tmp.resolve(name);
        StringBuilder sb = new StringBuilder(SettlementCsvParser.EXPECTED_HEADER).append('\n');
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(p, sb);
        return p;
    }

    private static String row(String id, String merchant, long amount) {
        return "%s,%s,%d,P2M,UPI_QR,STANDARD,0,2026-10-15T10:00:00Z".formatted(id, merchant, amount);
    }

    private long ingestSync(UUID tenant, Path file) throws IOException {
        PreparedBatch b = service.prepare(tenant, file);
        return service.loadRows(tenant, b.batchId(), file, b.recordedAt());
    }

    // ------------------------------------------------------------------ basics

    @Test
    void ingestsRowsAndResolvesMerchants() throws Exception {
        UUID tenant = newTenant("acme");
        Path f = csv("a.csv", List.of(
                row("T1", "shop1@upi", 300_000),
                row("T2", "shop1@upi", 500_000),
                row("T3", "shop2@upi", 100_000)));

        assertEquals(3, ingestSync(tenant, f));
        assertEquals(3, count("transaction_event"));
        assertEquals(2, count("merchant"), "two distinct merchant refs");
    }

    @Test
    void everyRowFromOneFileSharesOneRecordedAt() throws Exception {
        UUID tenant = newTenant("acme");
        Path f = csv("a.csv", List.of(
                row("T1", "s@upi", 300_000), row("T2", "s@upi", 400_000), row("T3", "s@upi", 500_000)));
        ingestSync(tenant, f);

        // A replay filtered on recorded_at must include the whole file or none of it.
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(DISTINCT recorded_at) FROM transaction_event", Long.class));
    }

    @Test
    void unexpectedHeaderIsRejectedBeforeAnyWork() throws Exception {
        Path p = tmp.resolve("wrong.csv");
        Files.writeString(p, "not,the,right,header\n1,2,3,4\n");
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(newTenant("acme"), p));
        assertEquals(0, count("ingestion_batch"));
    }

    // ------------------------------------------------------------- idempotency

    @Test
    void identicalBytesAreNotIngestedTwice() throws Exception {
        UUID tenant = newTenant("acme");
        Path f = csv("a.csv", List.of(row("T1", "s@upi", 300_000)));

        ingestSync(tenant, f);
        PreparedBatch second = service.prepare(tenant, f);

        assertTrue(second.alreadySeen());
        assertEquals(1, count("ingestion_batch"), "no second batch row");
    }

    @Test
    void differentContentIsIngestedEvenWithTheSameFilename() throws Exception {
        UUID tenant = newTenant("acme");
        ingestSync(tenant, csv("same.csv", List.of(row("T1", "s@upi", 300_000))));

        Path changed = csv("same.csv", List.of(row("T2", "s@upi", 400_000)));
        assertFalse(service.prepare(tenant, changed).alreadySeen(),
                "idempotency keys on content, not on filename");
    }

    /** A transient failure must not block a file forever. */
    @Test
    void aFailedBatchCanBeRetried() throws Exception {
        UUID tenant = newTenant("acme");
        Path f = csv("a.csv", List.of(row("T1", "s@upi", 300_000)));

        PreparedBatch first = service.prepare(tenant, f);
        jdbc.update("UPDATE ingestion_batch SET status='FAILED' WHERE id=?", first.batchId());

        assertFalse(service.prepare(tenant, f).alreadySeen(),
                "a FAILED batch must not make the file look already ingested");
    }

    // ------------------------------------------- regression: the two real bugs

    /**
     * Regression for the check-then-act race in merchant creation.
     *
     * <p>SELECT-then-INSERT has a window in which another thread inserts the
     * same merchant; the second INSERT then violates the unique constraint and
     * fails the whole batch. Sequential ingestion of 2,000,000 rows never hit
     * it. The first concurrent burst failed 12 of 14 batches.
     *
     * <p>Every file here deliberately references the same merchants.
     */
    @Test
    void concurrentBatchesSharingMerchantsDoNotCollide() throws Exception {
        UUID tenant = newTenant("acme");
        int files = 8, rowsPerFile = 60, merchants = 10;

        List<Path> paths = new ArrayList<>();
        List<PreparedBatch> batches = new ArrayList<>();
        for (int f = 0; f < files; f++) {
            List<String> rows = new ArrayList<>();
            for (int i = 0; i < rowsPerFile; i++) {
                rows.add(row("F%dT%d".formatted(f, i), "shop%d@upi".formatted(i % merchants), 300_000));
            }
            Path p = csv("f%d.csv".formatted(f), rows);
            paths.add(p);
            batches.add(service.prepare(tenant, p));
        }

        ExecutorService pool = Executors.newFixedThreadPool(files);
        List<Future<Long>> futures = new ArrayList<>();
        for (int f = 0; f < files; f++) {
            final int i = f;
            futures.add(pool.submit(() -> {
                // Each worker thread needs the tenant established: a
                // ThreadLocal does not follow work onto a pool thread, and
                // without it every insert would be rejected by the WITH CHECK
                // half of the row-level security policy.
                TenantContext.set(tenant);
                try {
                    return service.loadRows(tenant, batches.get(i).batchId(), paths.get(i), Instant.now());
                } finally {
                    TenantContext.clear();
                }
            }));
        }

        long total = 0;
        for (Future<Long> fut : futures) {
            // Before the fix this threw DuplicateKeyException from at least one thread.
            total += assertDoesNotThrow(() -> fut.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals((long) files * rowsPerFile, total);
        assertEquals(files * rowsPerFile, count("transaction_event"));
        assertEquals(merchants, count("merchant"), "each merchant created exactly once");
    }
}
