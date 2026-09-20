package in.reconpilot.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Triggering and inspecting reconciliation runs.
 *
 * <p>The run is synchronous here, which is the same mistake recorded as D2 for
 * ingestion. It is left that way deliberately for now so the timing is visible;
 * see D6 in KNOWN-DEFECTS.md. The fix is the pattern already built in ADR 0008.
 */
@RestController
public class ReconController {

    private final ReconciliationService recon;
    private final JdbcTemplate jdbc;

    public ReconController(ReconciliationService recon, JdbcTemplate jdbc) {
        this.recon = recon;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/recon/{batchId}")
    public ReconciliationResult run(@PathVariable UUID batchId) {
        return recon.reconcile(batchId);
    }

    /** Counts and money by break type. */
    @GetMapping("/api/breaks/summary")
    public List<BreakSummaryRow> summary() {
        return jdbc.query("""
                SELECT break_type,
                       count(*)                                   AS count,
                       COALESCE(sum(delta_paise), 0)              AS net_delta,
                       COALESCE(sum(GREATEST(delta_paise, 0)), 0) AS recoverable
                  FROM recon_break
                 GROUP BY break_type
                 ORDER BY sum(GREATEST(delta_paise, 0)) DESC
                """, (rs, i) -> new BreakSummaryRow(
                        rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)));
    }

    /**
     * Breaks, largest recoverable first: what an analyst should work through.
     *
     * <p>The left join to dispute lets a list view know which findings already
     * have a claim, without a request per row.
     */
    @GetMapping("/api/breaks")
    public List<BreakView> breaks(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(defaultValue = "50") int limit) {

        StringBuilder where = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        if (type != null)   { where.append(where.isEmpty() ? " WHERE " : " AND ").append("b.break_type = ?"); args.add(type); }
        if (status != null) { where.append(where.isEmpty() ? " WHERE " : " AND ").append("b.status = ?");     args.add(status); }

        String sql = """
                SELECT b.id, b.break_type, b.status, b.expected_mdr_paise, b.charged_mdr_paise,
                       b.delta_paise, t.external_txn_id, t.amount_paise, t.txn_type,
                       t.payment_rail, t.payee_category, d.id AS dispute_id, d.status AS dispute_status
                  FROM recon_break b
                  JOIN transaction_event t ON t.id = b.transaction_event_id
                  LEFT JOIN dispute d      ON d.break_id = b.id
                %s
                 ORDER BY b.delta_paise DESC
                 LIMIT %d
                """.formatted(where, Math.min(limit, 200));

        return jdbc.query(sql, (rs, i) -> new BreakView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getString(7),
                rs.getLong(8), rs.getString(9), rs.getString(10), rs.getString(11),
                rs.getObject(12, UUID.class), rs.getString(13)), args.toArray());
    }
}
