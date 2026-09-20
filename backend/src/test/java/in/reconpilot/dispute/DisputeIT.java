package in.reconpilot.dispute;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Filing is disabled here, matching the production default (defect D7).
 * The happy path through filing lives in {@link DisputeFilingEnabledIT}.
 */
class DisputeIT extends DisputeTestSupport {

    @Autowired DisputeService disputes;

    // ----------------------------------------------------------------- guards

    @Test
    void anUnderchargeCannotBeClaimed() {
        UUID brk = aBreakWithDelta(-500, "UNDERCHARGED");

        ClaimNotPermittedException e = assertThrows(ClaimNotPermittedException.class,
                () -> disputes.createFor(brk, "analyst"));

        // The PSP took too little. There is nothing to recover, and asking for
        // money we are not owed is simply wrong.
        assertTrue(e.getMessage().contains("Only an overcharge can be claimed"));
        assertEquals(0, count("dispute"));
    }

    @Test
    void aBreakCannotBeClaimedTwice() {
        UUID brk = aBreakWithDelta(600, "OVERCHARGED");
        disputes.createFor(brk, "analyst");

        assertThrows(ClaimNotPermittedException.class, () -> disputes.createFor(brk, "analyst"));
        assertEquals(1, count("dispute"), "a duplicate is not a second claim");
    }

    @Test
    void filingIsBlockedWhileTheRoundingRuleIsUnconfirmed() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");

        ClaimNotPermittedException e = assertThrows(ClaimNotPermittedException.class,
                () -> disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null));

        assertTrue(e.getMessage().contains("rounding rule is unconfirmed"));
        assertEquals("DRAFT", disputes.find(d.id()).status(), "state is unchanged after a refusal");
    }

    // ------------------------------------------------------------ transitions

    @Test
    void aClaimCannotBeSettledWithoutEverBeingFiled() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");

        assertThrows(IllegalTransitionException.class,
                () -> disputes.transition(d.id(), DisputeStatus.RECOVERED, "analyst", null, 600L));
        assertEquals("DRAFT", disputes.find(d.id()).status());
    }

    @Test
    void aWithdrawnClaimIsFinal() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");
        disputes.transition(d.id(), DisputeStatus.WITHDRAWN, "analyst", "our figure was wrong", null);

        assertThrows(IllegalTransitionException.class,
                () -> disputes.transition(d.id(), DisputeStatus.FILED, "analyst", null, null));
    }

    @Test
    void withdrawingWritesTheBreakOff() {
        UUID brk = aBreakWithDelta(600, "OVERCHARGED");
        DisputeView d = disputes.createFor(brk, "analyst");

        assertEquals("DISPUTED", jdbc.queryForObject(
                "SELECT status FROM recon_break WHERE id = ?", String.class, brk));

        disputes.transition(d.id(), DisputeStatus.WITHDRAWN, "analyst", "bad figure", null);

        assertEquals("WRITTEN_OFF", jdbc.queryForObject(
                "SELECT status FROM recon_break WHERE id = ?", String.class, brk));
    }

    @Test
    void aRecoveredAmountIsMeaninglessUnlessSettling() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");

        assertThrows(ClaimNotPermittedException.class,
                () -> disputes.transition(d.id(), DisputeStatus.WITHDRAWN, "analyst", null, 600L));
    }

    // --------------------------------------------------------------- history

    @Test
    void everyStateChangeIsRecorded() {
        DisputeView d = disputes.createFor(aBreakWithDelta(600, "OVERCHARGED"), "analyst");
        disputes.transition(d.id(), DisputeStatus.WITHDRAWN, "reviewer", "duplicate of another claim", null);

        List<DisputeEventView> history = disputes.history(d.id());

        assertEquals(2, history.size());
        assertNull(history.get(0).fromStatus(), "creation has no previous state");
        assertEquals("DRAFT", history.get(0).toStatus());
        assertEquals("analyst", history.get(0).actor());

        assertEquals("DRAFT", history.get(1).fromStatus());
        assertEquals("WITHDRAWN", history.get(1).toStatus());
        assertEquals("reviewer", history.get(1).actor());
        assertEquals("duplicate of another claim", history.get(1).note());
    }
}
