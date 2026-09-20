package in.reconpilot.dispute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static in.reconpilot.dispute.DisputeStatus.*;
import static org.junit.jupiter.api.Assertions.*;

/** The transition table is the specification, so it gets tested as one. */
class DisputeStatusTest {

    /**
     * Completeness. Adding a state without declaring its transitions would
     * otherwise fail at runtime with a NullPointerException, on whichever
     * unlucky request first encountered it.
     */
    @ParameterizedTest
    @EnumSource(DisputeStatus.class)
    void everyStateDeclaresItsTransitions(DisputeStatus s) {
        assertNotNull(s.allowedNext(), s + " has no transition entry");
    }

    @Test
    void aDraftCanOnlyBeFiledOrWithdrawn() {
        assertTrue(DRAFT.canTransitionTo(FILED));
        assertTrue(DRAFT.canTransitionTo(WITHDRAWN));
        assertFalse(DRAFT.canTransitionTo(ACCEPTED), "you cannot accept what was never filed");
        assertFalse(DRAFT.canTransitionTo(RECOVERED), "you cannot be paid for a claim you never made");
    }

    @Test
    void onlyAnAcceptedClaimCanBeSettled() {
        assertTrue(ACCEPTED.canTransitionTo(RECOVERED));
        assertFalse(FILED.canTransitionTo(RECOVERED));
        assertFalse(ACKNOWLEDGED.canTransitionTo(RECOVERED));
    }

    @Test
    void rejectionIsPossibleFromAnyLiveState() {
        assertTrue(FILED.canTransitionTo(REJECTED));
        assertTrue(ACKNOWLEDGED.canTransitionTo(REJECTED));
    }

    @DisplayName("Terminal states are genuinely final")
    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"RECOVERED", "REJECTED", "WITHDRAWN"})
    void terminalStatesHaveNoSuccessors(DisputeStatus terminal) {
        assertTrue(terminal.isTerminal());
        assertTrue(terminal.allowedNext().isEmpty());
        for (DisputeStatus any : DisputeStatus.values()) {
            assertFalse(terminal.canTransitionTo(any),
                    terminal + " must not move to " + any);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DisputeStatus.class, names = {"DRAFT", "FILED", "ACKNOWLEDGED", "ACCEPTED"})
    void liveStatesAreNotTerminal(DisputeStatus live) {
        assertFalse(live.isTerminal());
    }

    @Test
    void onlyRecoveryCountsAsSuccess() {
        assertTrue(RECOVERED.isSuccessful());
        assertFalse(ACCEPTED.isSuccessful(), "acceptance is a promise, not a payment");
        assertFalse(REJECTED.isSuccessful());
    }
}
