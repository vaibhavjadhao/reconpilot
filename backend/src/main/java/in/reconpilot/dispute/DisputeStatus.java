package in.reconpilot.dispute;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The life of a claim.
 *
 * <pre>
 *   DRAFT ──file──▶ FILED ──acknowledge──▶ ACKNOWLEDGED ──accept──▶ ACCEPTED ──settle──▶ RECOVERED
 *     │               │                         │
 *     │               └──reject──▶ REJECTED ◀───┘
 *     │                                         │
 *     └────────────── withdraw ──▶ WITHDRAWN ◀──┘
 * </pre>
 *
 * <p>The legal transitions live here as <b>data</b>, not as {@code if}
 * statements spread through a service. That matters for three reasons.
 *
 * <p>First, the rules are readable in one place: you can see the whole
 * lifecycle without tracing call paths. Second, adding a state forces you to
 * state its transitions, rather than discovering months later that some code
 * path never handled it. Third, and most importantly, an illegal transition
 * becomes <b>impossible by construction</b> rather than merely unlikely --
 * there is exactly one gate, and everything goes through it.
 *
 * <p>The alternative, a mutable {@code status} string updated wherever it is
 * convenient, is how records end up RECOVERED without ever having been FILED.
 */
public enum DisputeStatus {

    /** Created from a break, not yet sent to anyone. */
    DRAFT,

    /** Submitted to the PSP. */
    FILED,

    /** The PSP has confirmed receipt. */
    ACKNOWLEDGED,

    /** The PSP agrees it was overcharged. Money not yet received. */
    ACCEPTED,

    /** Money actually received. The only outcome that counts commercially. */
    RECOVERED,

    /** The PSP refused the claim. */
    REJECTED,

    /** We withdrew it, typically because our own figure was wrong. */
    WITHDRAWN;

    private static final Map<DisputeStatus, Set<DisputeStatus>> ALLOWED =
            new EnumMap<>(DisputeStatus.class);

    static {
        ALLOWED.put(DRAFT,        EnumSet.of(FILED, WITHDRAWN));
        ALLOWED.put(FILED,        EnumSet.of(ACKNOWLEDGED, REJECTED, WITHDRAWN));
        ALLOWED.put(ACKNOWLEDGED, EnumSet.of(ACCEPTED, REJECTED, WITHDRAWN));
        ALLOWED.put(ACCEPTED,     EnumSet.of(RECOVERED));
        // Terminal states. Nothing follows them, deliberately.
        ALLOWED.put(RECOVERED,    EnumSet.noneOf(DisputeStatus.class));
        ALLOWED.put(REJECTED,     EnumSet.noneOf(DisputeStatus.class));
        ALLOWED.put(WITHDRAWN,    EnumSet.noneOf(DisputeStatus.class));
    }

    public boolean canTransitionTo(DisputeStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<DisputeStatus> allowedNext() {
        return ALLOWED.get(this);
    }

    /** A terminal state has no successors; the claim is finished either way. */
    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /** Only a RECOVERED claim produced money. ACCEPTED is a promise, not a payment. */
    public boolean isSuccessful() {
        return this == RECOVERED;
    }
}
