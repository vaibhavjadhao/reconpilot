package in.reconpilot.mdr;

/**
 * Thrown when the published rules do not define an outcome.
 *
 * <p>Failing loudly is deliberate. A guessed figure in a reconciliation product
 * is worse than no figure: it is reported to a customer as fact, disputed with
 * a PSP on our behalf, and destroys the trust the product exists to sell.
 */
public class UnspecifiedRuleException extends RuntimeException {
    public UnspecifiedRuleException(String message) {
        super(message);
    }
}
