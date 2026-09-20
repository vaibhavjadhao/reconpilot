package in.reconpilot.recon;

/**
 * @param type        classification of the difference
 * @param deltaPaise  charged minus expected; positive means the merchant paid
 *                    too much, and is therefore money we can recover
 */
public record ReconOutcome(BreakType type, long deltaPaise) {

    public boolean isBreak() {
        return type != BreakType.NONE;
    }

    /** Only positive deltas are recoverable; an undercharge is not our money. */
    public long recoverablePaise() {
        return Math.max(0, deltaPaise);
    }
}
