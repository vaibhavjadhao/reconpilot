package in.reconpilot.format;

/** Discovery could not produce a usable mapping. Carries a reason a human can act on. */
public class FormatDiscoveryException extends RuntimeException {
    public FormatDiscoveryException(String message) {
        super(message);
    }
    public FormatDiscoveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
