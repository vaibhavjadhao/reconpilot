package in.reconpilot.dispute;

/**
 * A claim that must not be made, for a business reason rather than a
 * technical one -- for example claiming money that is not ours, or filing
 * while a known correctness risk is unresolved.
 */
public class ClaimNotPermittedException extends RuntimeException {
    public ClaimNotPermittedException(String message) {
        super(message);
    }
}
