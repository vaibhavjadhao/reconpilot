package in.reconpilot.recon;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
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
    public List<Map<String, Object>> summary() {
        return jdbc.queryForList("""
                SELECT break_type,
                       count(*)                                     AS count,
                       sum(delta_paise)                             AS net_delta_paise,
                       sum(GREATEST(delta_paise, 0))                AS recoverable_paise
                  FROM recon_break
                 GROUP BY break_type
                 ORDER BY sum(GREATEST(delta_paise, 0)) DESC
                """);
    }

    /** The largest recoverable breaks: what an analyst should look at first. */
    @GetMapping("/api/breaks")
    public List<Map<String, Object>> breaks(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit) {

        String sql = """
                SELECT b.break_type, b.expected_mdr_paise, b.charged_mdr_paise,
                       b.delta_paise, b.status, t.external_txn_id, t.amount_paise,
                       t.txn_type, t.payment_rail, t.payee_category
                  FROM recon_break b
                  JOIN transaction_event t ON t.id = b.transaction_event_id
                 %s
                 ORDER BY b.delta_paise DESC
                 LIMIT %d
                """.formatted(type == null ? "" : "WHERE b.break_type = ?", Math.min(limit, 200));

        return type == null ? jdbc.queryForList(sql) : jdbc.queryForList(sql, type);
    }
}
