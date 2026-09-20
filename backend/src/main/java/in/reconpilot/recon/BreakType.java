package in.reconpilot.recon;

/**
 * How a charge differs from what the rules require.
 *
 * <p>These are not merely arithmetic labels. Each one becomes a different
 * sentence in a dispute, with different strength, so the distinction is worth
 * keeping even where the money is identical.
 */
public enum BreakType {
    /** Charged and computed agree. Not a break. */
    NONE,

    /** MDR was charged where the rules require zero. The strongest claim. */
    CHARGED_WHEN_EXEMPT,

    /** Charged above the Rs 300 per-transaction cap. A clear, citable breach. */
    CAP_BREACHED,

    /** Charged more than the rules require, within the cap. */
    OVERCHARGED,

    /** Charged less than the rules require. Reported, never claimed. */
    UNDERCHARGED
}
