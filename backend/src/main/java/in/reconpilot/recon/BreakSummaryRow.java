package in.reconpilot.recon;

/**
 * @param netDeltaPaise     sum of all deltas, which can be negative for undercharges
 * @param recoverablePaise  sum of positive deltas only -- the money we could claim
 */
public record BreakSummaryRow(
        String breakType,
        long count,
        long netDeltaPaise,
        long recoverablePaise
) {}
