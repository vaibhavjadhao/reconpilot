package in.reconpilot.marketplace;

/**
 * Thrown when no published rate covers the input.
 *
 * <p>The alternative -- falling back to a default rate -- would produce a
 * number that looks authoritative and is invented. Every figure this system
 * shows a customer is one it can point at a source for.
 */
public class UnpublishedRateException extends RuntimeException {
    public UnpublishedRateException(String message) {
        super(message);
    }
}
