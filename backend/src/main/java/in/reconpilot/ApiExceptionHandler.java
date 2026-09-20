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

    /** A negative amount is the caller's mistake, so it is a 400, not a 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleIllegalArgument(IllegalArgumentException e) {
        return Map.of(
                "error", "INVALID_REQUEST",
                "message", e.getMessage());
    }
}
