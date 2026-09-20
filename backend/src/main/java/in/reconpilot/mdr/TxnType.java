package in.reconpilot.mdr;

/** Transaction classification as reported by the source. */
public enum TxnType {
    /** Person to person. Never attracts MDR (FAQ Q16). */
    P2P,
    /** Standard merchant. */
    P2M,
    /** Micro merchant under the Rs 1 lakh/month category. Exempt (FAQ Q23, Q26). */
    P2PM
}
