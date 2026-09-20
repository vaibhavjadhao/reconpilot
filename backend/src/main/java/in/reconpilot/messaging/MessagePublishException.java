package in.reconpilot.messaging;

/**
 * The broker did not accept the message.
 *
 * <p>Distinct from an ordinary failure because the caller must undo whatever it
 * recorded before publishing: work that was never queued must leave no trace,
 * or the idempotency check will later report it as already done.
 */
public class MessagePublishException extends RuntimeException {
    public MessagePublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
