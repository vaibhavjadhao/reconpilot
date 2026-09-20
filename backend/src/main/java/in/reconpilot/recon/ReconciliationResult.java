package in.reconpilot.recon;

import java.util.Map;
import java.util.UUID;

/**
 * @param scanned         transactions examined
 * @param breaksFound     breaks written
 * @param byType          counts per {@link BreakType}
 * @param recoverablePaise total of the positive deltas -- money we can claim
 */
public record ReconciliationResult(
        UUID batchId,
        long scanned,
        long breaksFound,
        Map<String, Long> byType,
        long recoverablePaise,
        long millis
) {}
