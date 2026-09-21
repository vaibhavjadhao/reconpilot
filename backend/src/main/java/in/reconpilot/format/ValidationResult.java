package in.reconpilot.format;

import java.util.List;

/**
 * @param problems every reason the mapping was rejected, not just the first --
 *                 a human fixing a mapping wants the whole list
 */
public record ValidationResult(boolean ok, List<String> problems) {

    public static ValidationResult pass() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(List<String> problems) {
        return new ValidationResult(false, List.copyOf(problems));
    }

    public String summary() {
        return ok ? "validated against sample rows" : String.join("; ", problems);
    }
}
