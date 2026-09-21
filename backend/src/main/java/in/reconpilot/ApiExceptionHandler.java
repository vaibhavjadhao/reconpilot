package in.reconpilot;

import in.reconpilot.mdr.UnspecifiedRuleException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Error handling shared across every controller.
 *
 * <p>An unspecified rule is not a server fault. Returning 500 would tell a
 * customer we are broken, when in fact we are deliberately refusing to invent a
 * rate the regulator has not published. 501 states that the request was
 * understood and cannot be served.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnspecifiedRuleException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public Map<String, String> handleUnspecifiedRule(UnspecifiedRuleException e) {
        return Map.of(
                "error", "RULE_NOT_SPECIFIED",
                "message", e.getMessage());
    }

    /**
     * The ingestion pool is full: both its threads and its queue.
     *
     * <p>503 with Retry-After is the honest answer -- the request was valid,
     * we are simply at capacity right now. Silently queueing it instead would
     * trade a visible rejection for an invisible OutOfMemoryError later.
     */
    @ExceptionHandler(java.util.concurrent.RejectedExecutionException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleOverloaded(java.util.concurrent.RejectedExecutionException e) {
        return Map.of(
                "error", "INGESTION_BUSY",
                "message", "All ingestion workers are occupied. Retry shortly.");
    }

    /**
     * The request conflicts with the resource's current state -- for example
     * settling a claim that was never filed. 409 says "not now", where 400
     * would wrongly imply the request was malformed.
     */
    @ExceptionHandler(in.reconpilot.dispute.IllegalTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleIllegalTransition(
            in.reconpilot.dispute.IllegalTransitionException e) {
        return Map.of("error", "ILLEGAL_TRANSITION", "message", e.getMessage());
    }

    /**
     * Well-formed and understood, but not allowed by a business rule -- such as
     * claiming money that is not ours. 422 rather than 400 because nothing is
     * wrong with the request's syntax.
     */
    @ExceptionHandler(in.reconpilot.dispute.ClaimNotPermittedException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> handleClaimNotPermitted(
            in.reconpilot.dispute.ClaimNotPermittedException e) {
        return Map.of("error", "CLAIM_NOT_PERMITTED", "message", e.getMessage());
    }

    /**
     * Format discovery could not run or could not produce a usable mapping.
     *
     * <p>503 rather than 500: the request was fine and the service is healthy;
     * a dependency it needs is unavailable or unconfigured. The message says
     * which, because "discovery failed" sends someone to read logs while
     * "no Anthropic credentials found" is actionable immediately.
     */
    @ExceptionHandler(in.reconpilot.format.FormatDiscoveryException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleFormatDiscovery(
            in.reconpilot.format.FormatDiscoveryException e) {
        return Map.of("error", "FORMAT_DISCOVERY_UNAVAILABLE", "message", e.getMessage());
    }

    /** A negative amount is the caller's mistake, so it is a 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of(
                "error", "INVALID_REQUEST",
                "message", e.getMessage());
    }
}
