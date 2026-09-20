package in.reconpilot.dispute;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns findings into claims, and moves those claims through their life.
 *
 * <p>Every state change goes through {@link #transition}, which is the single
 * gate: it checks the transition is legal, records an immutable event, and only
 * then updates the current state. There is deliberately no other way to change
 * a dispute's status.
 */
@Service
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final JdbcTemplate jdbc;

    /**
     * Whether claims may actually be filed with a PSP.
     *
     * <p>Defaults to false because of D7: our rounding rule is provisional
     * pending the NPCI circular, and a one-paise disagreement produced 8,174
     * false breaks in a single run. Filing those would mean disputing charges
     * that were correct, which destroys the credibility the product sells.
     *
     * <p>Drafting, reviewing and withdrawing all work regardless. Only the
     * step that sends something outward is gated.
     */
    private final boolean filingEnabled;

    public DisputeService(JdbcTemplate jdbc,
                          @Value("${reconpilot.claims.filing-enabled:false}") boolean filingEnabled) {
        this.jdbc = jdbc;
        this.filingEnabled = filingEnabled;
    }

    // ------------------------------------------------------------------ create

    /**
     * Raises a draft claim for a break.
     *
     * <p>Refuses to claim money that is not ours. An UNDERCHARGED break has a
     * negative delta: the PSP took too little, so there is nothing to recover
     * and asking for money would be simply wrong. The engine reports those for
     * completeness; it must never claim them.
     */
    @Transactional
    public DisputeView createFor(UUID breakId, String actor) {
        Map<String, Object> b = jdbc.queryForMap("""
                SELECT id, tenant_id, delta_paise, break_type, status
                  FROM recon_break WHERE id = ?
                """, breakId);

        long delta = ((Number) b.get("delta_paise")).longValue();
        if (delta <= 0) {
            throw new ClaimNotPermittedException(
                    "Break %s has a delta of %d paise. Only an overcharge can be claimed; an %s break is reported, never disputed."
                            .formatted(breakId, delta, b.get("break_type")));
        }

        UUID tenantId = (UUID) b.get("tenant_id");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        try {
            jdbc.update("""
                    INSERT INTO dispute (id, tenant_id, break_id, status, claimed_paise, created_at)
                    VALUES (?, ?, ?, 'DRAFT', ?, ?)
                    """, id, tenantId, breakId, delta, Timestamp.from(now));
        } catch (DuplicateKeyException e) {
            // Enforced by uq_dispute_per_break. A PSP rejects duplicate claims,
            // and rejected duplicates damage credibility on the real ones.
            throw new ClaimNotPermittedException(
                    "Break %s already has a dispute. Filing it twice is a duplicate, not a second claim."
                            .formatted(breakId));
        }

        recordEvent(id, null, DisputeStatus.DRAFT, actor, "Raised from break", null, now);
        jdbc.update("UPDATE recon_break SET status = 'DISPUTED' WHERE id = ?", breakId);

        log.info("Dispute {} raised for break {} claiming {} paise", id, breakId, delta);
        return find(id);
    }

    // -------------------------------------------------------------- transition

    /**
     * The single gate through which every state change passes.
     *
     * @param recoveredPaise required when moving to RECOVERED, forbidden otherwise
     */
    @Transactional
    public DisputeView transition(UUID disputeId, DisputeStatus target, String actor,
                                  String note, Long recoveredPaise) {

        Map<String, Object> d = jdbc.queryForMap(
                "SELECT status, claimed_paise, break_id FROM dispute WHERE id = ?", disputeId);

        DisputeStatus current = DisputeStatus.valueOf((String) d.get("status"));
        long claimed = ((Number) d.get("claimed_paise")).longValue();
        UUID breakId = (UUID) d.get("break_id");

        if (!current.canTransitionTo(target)) {
            throw new IllegalTransitionException(current, target);
        }

        if (target == DisputeStatus.FILED && !filingEnabled) {
            throw new ClaimNotPermittedException(
                    "Filing is disabled. The MDR rounding rule is unconfirmed (open question 5, defect D7), "
                  + "and a one-paise disagreement produced 8,174 false breaks in testing. "
                  + "Set reconpilot.claims.filing-enabled=true only once the rule is confirmed "
                  + "or the engine is calibrated against the PSP's own figures.");
        }

        if (target == DisputeStatus.RECOVERED) {
            if (recoveredPaise == null) {
                throw new ClaimNotPermittedException("Settling a claim requires the amount recovered.");
            }
            if (recoveredPaise < 0) {
                throw new ClaimNotPermittedException("Recovered amount cannot be negative.");
            }
            // Partial recovery is normal; recovering more than claimed is not.
            if (recoveredPaise > claimed) {
                throw new ClaimNotPermittedException(
                        "Recovered %d paise exceeds the %d paise claimed. Money cannot be created by settling."
                                .formatted(recoveredPaise, claimed));
            }
        } else if (recoveredPaise != null) {
            throw new ClaimNotPermittedException(
                    "A recovered amount only makes sense when settling, not when moving to " + target);
        }

        Instant now = Instant.now();
        jdbc.update("""
                UPDATE dispute
                   SET status = ?,
                       recovered_paise = COALESCE(?, recovered_paise),
                       filed_at    = CASE WHEN ? = 'FILED' THEN ? ELSE filed_at END,
                       resolved_at = CASE WHEN ? THEN ? ELSE resolved_at END
                 WHERE id = ?
                """,
                target.name(), recoveredPaise,
                target.name(), Timestamp.from(now),
                target.isTerminal(), Timestamp.from(now),
                disputeId);

        recordEvent(disputeId, current, target, actor, note, recoveredPaise, now);

        if (target.isTerminal()) {
            jdbc.update("UPDATE recon_break SET status = ? WHERE id = ?",
                    target.isSuccessful() ? "RESOLVED" : "WRITTEN_OFF", breakId);
        }

        log.info("Dispute {}: {} -> {} by {}", disputeId, current, target, actor);
        return find(disputeId);
    }

    // ------------------------------------------------------------------ reads

    public DisputeView find(UUID id) {
        return jdbc.queryForObject("""
                SELECT id, break_id, status, claimed_paise, recovered_paise,
                       external_reference, created_at, filed_at, resolved_at
                  FROM dispute WHERE id = ?
                """, DisputeService::mapDispute, id);
    }

    public List<DisputeEventView> history(UUID disputeId) {
        return jdbc.query("""
                SELECT from_status, to_status, actor, note, recovered_paise, occurred_at
                  FROM dispute_event WHERE dispute_id = ? ORDER BY occurred_at, id
                """, (rs, i) -> new DisputeEventView(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5) == null ? null : rs.getLong(5),
                        rs.getTimestamp(6).toInstant()), disputeId);
    }

    public List<DisputeView> list(String status, int limit) {
        String sql = """
                SELECT id, break_id, status, claimed_paise, recovered_paise,
                       external_reference, created_at, filed_at, resolved_at
                  FROM dispute %s ORDER BY created_at DESC LIMIT %d
                """.formatted(status == null ? "" : "WHERE status = ?", Math.min(limit, 200));
        return status == null
                ? jdbc.query(sql, DisputeService::mapDispute)
                : jdbc.query(sql, DisputeService::mapDispute, status);
    }

    /** Claimed vs actually recovered, which are very different numbers. */
    public Map<String, Object> summary() {
        return jdbc.queryForMap("""
                SELECT count(*)                                                        AS total,
                       count(*) FILTER (WHERE status = 'RECOVERED')                    AS recovered_count,
                       COALESCE(sum(claimed_paise), 0)                                 AS claimed_paise,
                       COALESCE(sum(recovered_paise) FILTER (WHERE status = 'RECOVERED'), 0) AS recovered_paise
                  FROM dispute
                """);
    }

    // ---------------------------------------------------------------- internals

    private void recordEvent(UUID disputeId, DisputeStatus from, DisputeStatus to,
                             String actor, String note, Long recoveredPaise, Instant at) {
        jdbc.update("""
                INSERT INTO dispute_event
                    (id, dispute_id, from_status, to_status, actor, note, recovered_paise, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), disputeId,
                from == null ? null : from.name(), to.name(),
                actor, note, recoveredPaise, Timestamp.from(at));
    }

    private static DisputeView mapDispute(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DisputeView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getLong(4),
                rs.getObject(5) == null ? null : rs.getLong(5),
                rs.getString(6),
                rs.getTimestamp(7).toInstant(),
                rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant());
    }
}
