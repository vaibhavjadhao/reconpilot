package in.reconpilot.recon;

import in.reconpilot.AbstractIntegrationTest;
import in.reconpilot.ingest.IngestionService;
import in.reconpilot.ingest.PreparedBatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReconciliationIT extends AbstractIntegrationTest {

    private static final String HEADER =
            "txn_id,merchant_vpa,amount_paise,txn_type,rail,payee_category,charged_mdr_paise,occurred_at";

    @Autowired IngestionService ingestion;
    @Autowired ReconciliationService recon;

    @TempDir Path tmp;

    private final List<String> rows = new ArrayList<>();

    /** @param charged what the PSP says it took, which may be wrong on purpose */
    private void add(String id, long amountPaise, String type, String rail, String cat, long charged) {
        rows.add("%s,shop1@upi,%d,%s,%s,%s,%d,2026-10-15T10:00:00Z"
                .formatted(id, amountPaise, type, rail, cat, charged));
    }

    private UUID ingestRows() throws IOException {
        Path p = tmp.resolve("t.csv");
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(p, sb);

        UUID tenant = newTenant("acme");
        PreparedBatch b = ingestion.prepare(tenant, p);
        ingestion.loadRows(tenant, b.batchId(), p, b.recordedAt());
        return b.batchId();
    }

    @Test
    void correctChargesProduceNoBreaks() throws Exception {
        // Figures the regulator states in FAQ Q35.
        add("A", 300_000,    "P2M",  "UPI_QR", "STANDARD",         1_200);   // Rs 3,000 -> Rs 12
        add("B", 5_000_000,  "P2M",  "UPI_QR", "STANDARD",        20_000);   // Rs 50,000 -> Rs 200
        add("C", 10_000_000, "P2M",  "UPI_QR", "STANDARD",        30_000);   // capped at Rs 300
        add("D", 150_000,    "P2M",  "UPI_QR", "STANDARD",             0);   // below threshold
        add("E", 9_000_000,  "P2PM", "UPI_QR", "STANDARD",             0);   // exempt category
        add("F", 1_000_000,  "P2M",  "UPI_AUTOPAY", "STANDARD",        0);   // exempt rail
        add("G", 5_000_000,  "P2M",  "UPI_QR", "CAPITAL_MARKETS",  1_000);   // 0.02%
        add("H", 300_000,    "P2M",  "UPI_QR", "INDUSTRY_PROGRAM",   500);   // flat Rs 5

        ReconciliationResult r = recon.reconcile(ingestRows());

        assertEquals(8, r.scanned());
        assertEquals(0, r.breaksFound(), () -> "unexpected breaks: " + r.byType());
        assertEquals(0, count("recon_break"));
    }

    @Test
    void findsExactlyThePlantedErrors() throws Exception {
        add("OK1", 300_000,   "P2M",  "UPI_QR", "STANDARD",  1_200);          // correct
        add("OK2", 5_000_000, "P2M",  "UPI_QR", "STANDARD", 20_000);          // correct

        add("OVER",   300_000,    "P2M",  "UPI_QR", "STANDARD", 1_800);       // +600
        add("UNDER",  5_000_000,  "P2M",  "UPI_QR", "STANDARD", 19_000);      // -1000
        add("EXEMPT", 9_000_000,  "P2PM", "UPI_QR", "STANDARD", 2_500);       // owed nothing
        add("CAP",    10_000_000, "P2M",  "UPI_QR", "STANDARD", 45_000);      // above Rs 300

        ReconciliationResult r = recon.reconcile(ingestRows());

        assertEquals(6, r.scanned());
        assertEquals(4, r.breaksFound());
        assertEquals(1L, r.byType().get("OVERCHARGED"));
        assertEquals(1L, r.byType().get("UNDERCHARGED"));
        assertEquals(1L, r.byType().get("CHARGED_WHEN_EXEMPT"));
        assertEquals(1L, r.byType().get("CAP_BREACHED"));

        // Only positive deltas are ours to claim: 600 + 2500 + 15000.
        assertEquals(18_100, r.recoverablePaise());
    }

    /**
     * With no published rate there is no defensible expectation, so there can
     * be no claim. Asserting a break here would mean disputing a charge under
     * a rule we admit we do not know.
     */
    @Test
    void transactionsWithNoPublishedRuleAreSkippedNotBroken() throws Exception {
        add("EDU",    500_000, "P2M", "UPI_QR",          "EDUCATION", 9_999);
        add("CREDIT", 500_000, "P2M", "UPI_CREDIT_LINE", "STANDARD",  9_999);
        add("OK",     300_000, "P2M", "UPI_QR",          "STANDARD",  1_200);

        ReconciliationResult r = recon.reconcile(ingestRows());

        assertEquals(3, r.scanned());
        assertEquals(0, r.breaksFound(), "an unknown rule is not a break");
        assertEquals(2L, r.byType().get("SKIPPED_UNSPECIFIED"));
    }

    /**
     * Re-running is normal: after a restatement, a rules change, or a double
     * click. As with ingestion, the guarantee is a database constraint rather
     * than application logic.
     */
    @Test
    void rerunningDoesNotDuplicateBreaks() throws Exception {
        add("OVER", 300_000, "P2M", "UPI_QR", "STANDARD", 1_800);
        UUID batch = ingestRows();

        assertEquals(1, recon.reconcile(batch).breaksFound());
        assertEquals(1, count("recon_break"));

        recon.reconcile(batch);
        recon.reconcile(batch);

        assertEquals(1, count("recon_break"), "one transaction yields at most one break");
    }

    /** Paging must not drop or double-count rows at a page boundary. */
    @Test
    void scansEveryRowAcrossPageBoundaries() throws Exception {
        int n = 12_000;                       // more than two 5,000-row pages
        for (int i = 0; i < n; i++) {
            add("T" + i, 300_000, "P2M", "UPI_QR", "STANDARD", i % 3 == 0 ? 1_800 : 1_200);
        }

        ReconciliationResult r = recon.reconcile(ingestRows());

        assertEquals(n, r.scanned(), "every row seen exactly once");
        long expectedBreaks = (n + 2) / 3;
        assertEquals(expectedBreaks, r.breaksFound());
        assertEquals(expectedBreaks, count("recon_break"));
    }
}
