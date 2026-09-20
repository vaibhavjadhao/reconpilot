package in.reconpilot.recon;

import in.reconpilot.mdr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Recomputes MDR for every transaction in a batch and records the differences.
 *
 * <h2>Why this walks the table in chunks</h2>
 *
 * The obvious implementation loads the batch and loops over it. That is the
 * same mistake as reading a file with readAllLines: a million rows held at once
 * costs hundreds of megabytes, and the cost grows with the customer.
 *
 * The next idea is a single streaming cursor. That works, but it holds one
 * database transaction open for the entire run. Long transactions block
 * vacuum, accumulate WAL, and make the whole run an all-or-nothing unit that
 * cannot resume.
 *
 * So we page. And we page by <b>keyset</b>, not by OFFSET:
 *
 * <pre>
 *   OFFSET 500000 LIMIT 5000   -- the database reads 505,000 rows, discards 500,000
 *   WHERE id > ? LIMIT 5000    -- the index seeks straight to the position
 * </pre>
 *
 * OFFSET pagination gets slower with every page, because the database must
 * count past everything it already gave you. Over a million rows that is
 * quadratic work. Keyset pagination remembers where it stopped, so every page
 * costs the same as the first.
 *
 * <p>Writes reuse the idempotency pattern from ingestion: a unique index on
 * {@code transaction_event_id} plus ON CONFLICT DO NOTHING, so re-running a
 * reconciliation -- after a restatement, a rules change, or a double click --
 * cannot duplicate findings.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private static final int PAGE = 5_000;
    private static final String RULESET_VERSION = "npci-2026-10-15";

    private static final String PAGE_SQL = """
            SELECT id, tenant_id, amount_paise, txn_type, payment_rail,
                   payee_category, charged_mdr_paise
              FROM transaction_event
             WHERE batch_id = ? AND id > ?
             ORDER BY id
             LIMIT %d
            """.formatted(PAGE);

    private static final String INSERT_BREAK = """
            INSERT INTO recon_break
                (id, tenant_id, transaction_event_id, expected_mdr_paise,
                 charged_mdr_paise, delta_paise, break_type, status, ruleset_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
            ON CONFLICT (transaction_event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final MdrCalculator calculator;
    private final MdrComparator comparator;

    public ReconciliationService(JdbcTemplate jdbc, MdrCalculator calculator, MdrComparator comparator) {
        this.jdbc = jdbc;
        this.calculator = calculator;
        this.comparator = comparator;
    }

    public ReconciliationResult reconcile(UUID batchId) {
        long t0 = System.currentTimeMillis();

        UUID cursor = new UUID(0L, 0L);       // smallest possible UUID
        long scanned = 0, breaks = 0, recoverable = 0;
        Map<String, Long> byType = new TreeMap<>();
        List<Object[]> pending = new ArrayList<>(PAGE);

        while (true) {
            List<Row> page = jdbc.query(PAGE_SQL, ReconciliationService::mapRow, batchId, cursor);
            if (page.isEmpty()) break;

            for (Row r : page) {
                scanned++;
                cursor = r.id();

                long expected;
                try {
                    expected = calculator.calculate(new MdrInput(
                            r.amountPaise(), r.txnType(), r.rail(), r.payeeCategory())).mdrPaise();
                } catch (UnspecifiedRuleException e) {
                    // No published rate, so no defensible expectation, so no
                    // claim. Skipping is the honest outcome: asserting a break
                    // here would mean disputing a charge on a rule we admit we
                    // do not know.
                    byType.merge("SKIPPED_UNSPECIFIED", 1L, Long::sum);
                    continue;
                }

                ReconOutcome outcome = comparator.compare(expected, r.chargedMdrPaise());
                if (!outcome.isBreak()) continue;

                breaks++;
                recoverable += outcome.recoverablePaise();
                byType.merge(outcome.type().name(), 1L, Long::sum);

                pending.add(new Object[]{
                        UUID.randomUUID(), r.tenantId(), r.id(), expected,
                        r.chargedMdrPaise(), outcome.deltaPaise(),
                        outcome.type().name(), RULESET_VERSION});
            }

            if (!pending.isEmpty()) {
                jdbc.batchUpdate(INSERT_BREAK, pending);
                pending.clear();
            }
            if (page.size() < PAGE) break;     // last page
        }

        long ms = System.currentTimeMillis() - t0;
        log.info("Reconciled batch {}: {} scanned, {} breaks, Rs {} recoverable, {} ms",
                batchId, scanned, breaks, recoverable / 100, ms);

        return new ReconciliationResult(batchId, scanned, breaks, byType, recoverable, ms);
    }

    private record Row(UUID id, UUID tenantId, long amountPaise, TxnType txnType,
                       PaymentRail rail, PayeeCategory payeeCategory, long chargedMdrPaise) {}

    private static Row mapRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Row(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                TxnType.valueOf(rs.getString(4)),
                PaymentRail.valueOf(rs.getString(5)),
                PayeeCategory.valueOf(rs.getString(6)),
                rs.getLong(7));
    }
}
