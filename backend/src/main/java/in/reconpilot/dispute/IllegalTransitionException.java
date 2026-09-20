package in.reconpilot.dispute;

/** An attempt to move a claim to a state that does not follow its current one. */
public class IllegalTransitionException extends RuntimeException {
    public IllegalTransitionException(DisputeStatus from, DisputeStatus to) {
        super("Cannot move a dispute from %s to %s. Allowed from %s: %s"
                .formatted(from, to, from, from.allowedNext()));
    }
}
