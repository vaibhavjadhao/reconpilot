package in.reconpilot.format;

/**
 * Whether a source states amounts in rupees or paise.
 *
 * <p>Getting this wrong is a factor-of-100 error in every figure, so it is
 * asked for explicitly rather than guessed from the data, and then checked
 * against real rows before the mapping is trusted.
 */
public enum AmountUnit {
    PAISE(1L),
    RUPEES(100L);

    private final long toPaise;

    AmountUnit(long toPaise) {
        this.toPaise = toPaise;
    }

    /** Integer arithmetic only: rupees arrive as a decimal string, never a double. */
    public long toPaise(String raw) {
        java.math.BigDecimal v = new java.math.BigDecimal(raw.trim().replace(",", ""));
        return v.multiply(java.math.BigDecimal.valueOf(toPaise)).longValueExact();
    }
}
