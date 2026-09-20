package in.reconpilot.mdr;

/**
 * The computed MDR and, just as importantly, which rule produced it.
 *
 * <p>Several rules yield zero for different reasons with different lifespans:
 * an AutoPay exemption survives a merchant's reclassification to P2M, a P2PM
 * exemption does not. Storing only the amount makes a correct figure
 * unexplainable (see ADR 0006).
 *
 * @param mdrPaise  computed MDR in paise
 * @param ruleId    identifier of the rule that decided this result
 */
public record MdrResult(long mdrPaise, String ruleId) {
}
