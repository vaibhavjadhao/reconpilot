package in.reconpilot.dispute;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The happy path, with filing switched on.
 *
 * <p>Its own class because {@code @TestPropertySource} changes the Spring
 * context, and because the production default is deliberately the opposite.
 */
@TestPropertySource(properties = "reconpilot.claims.filing-enabled=true")
class DisputeFilingEnabledIT extends DisputeTestSupport {

    @Autowired DisputeService disputes;

    @Test
    void aClaimCanBeTakenAllTheWayToRecovery() {
        UUID brk = aBreakWithDelta(600, "OVERCHARGED");

        DisputeView d = disputes.createFor(brk, "analyst");
        assertEquals("DRAFT", d.status());
        assertEquals(600, d.claimedPaise());

        disputes.transition(d.id(), DisputeStatus.FILED, "analyst", "filed with PSP", null);
        disputes.transition(d.id(), DisputeStatus.ACKNOWLEDGED, "psp-webhook", "ticket PSP-123", null);
        disputes.transition(d.id(), DisputeStatus.ACCEPTED, "psp-webhook", "agreed", null);
        DisputeView settled = disputes.transition(
                d.id(), DisputeStatus.RECOVERED, "finance", "credited", 600L);

        assertEquals("RECOVERED", settled.status());
        assertEquals(600, settled.recoveredPaise());
        assertNotNull(settled.filedAt());
        assertNotNull(settled.resolvedAt());

        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM recon_break WHERE id = ?", String.class, brk));

        List<DisputeEventView> history = disputes.history(d.id());
        assertEquals(5, history.size(), "creation plus four transitions");
        assertEquals(600L, history.getLast().recoveredPaise());
    }

    /** PSPs often settle for less than claimed. That is normal and allowed. */
    @Test
    void partialRecoveryIsAllowed() {
        DisputeView d = disputes.createFor(aBreakWithDelta(1_000, "OVERCHARGED"), "analyst");
        disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(d.id(), DisputeStatus.ACKNOWLEDGED, "psp", null, null);
        disputes.transition(d.id(), DisputeStatus.ACCEPTED, "psp", null, null);

        DisputeView settled = disputes.transition(d.id(), DisputeStatus.RECOVERED, "finance", null, 400L);
        assertEquals(400, settled.recoveredPaise());
    }

    /** Settling cannot invent money. */
    @Test
    void recoveringMoreThanClaimedIsRefused() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");
        disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(d.id(), DisputeStatus.ACKNOWLEDGED, "psp", null, null);
        disputes.transition(d.id(), DisputeStatus.ACCEPTED, "psp", null, null);

        ClaimNotPermittedException e = assertThrows(ClaimNotPermittedException.class,
                () -> disputes.transition(d.id(), DisputeStatus.RECOVERED, "finance", null, 9_999L));
        assertTrue(e.getMessage().contains("Money cannot be created"));
    }

    @Test
    void settlingRequiresAnAmount() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");
        disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(d.id(), DisputeStatus.ACKNOWLEDGED, "psp", null, null);
        disputes.transition(d.id(), DisputeStatus.ACCEPTED, "psp", null, null);

        assertThrows(ClaimNotPermittedException.class,
                () -> disputes.transition(d.id(), DisputeStatus.RECOVERED, "finance", null, null));
    }

    /** A rejected claim is finished, and the break is written off, not resolved. */
    @Test
    void rejectionWritesTheBreakOff() {
        UUID brk = aBreakWithDelta(600, "OVERCHARGED");
        DisputeView d = disputes.createFor(brk, "analyst");
        disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(d.id(), DisputeStatus.REJECTED, "psp", "fee was correct per contract", null);

        assertEquals("WRITTEN_OFF", jdbc.queryForObject(
                "SELECT status FROM recon_break WHERE id = ?", String.class, brk));
    }

    /** Claimed and recovered are different numbers, and only one is real money. */
    @Test
    void summaryDistinguishesClaimedFromRecovered() {
        DisputeView a = disputes.createFor(aBreakWithDelta(1_000, "OVERCHARGED"), "analyst");
        disputes.transition(a.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(a.id(), DisputeStatus.ACKNOWLEDGED, "psp", null, null);
        disputes.transition(a.id(), DisputeStatus.ACCEPTED, "psp", null, null);
        disputes.transition(a.id(), DisputeStatus.RECOVERED, "finance", null, 700L);

        DisputeView b = disputes.createFor(aBreakWithDelta(500, "OVERCHARGED"), "analyst");
        disputes.transition(b.id(), DisputeStatus.FILED, "analyst", null, null);
        disputes.transition(b.id(), DisputeStatus.REJECTED, "psp", null, null);

        Map<String, Object> s = disputes.summary();
        assertEquals(2L, ((Number) s.get("total")).longValue());
        assertEquals(1L, ((Number) s.get("recovered_count")).longValue());
        assertEquals(1_500L, ((Number) s.get("claimed_paise")).longValue());
        assertEquals(700L, ((Number) s.get("recovered_paise")).longValue(),
                "only money actually received counts");
    }
}
