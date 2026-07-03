package app.readoption.scoring;

import java.math.BigDecimal;

public enum AdpBucket {
    STANDARD,
    HALF_PPR,
    PPR;

    private static final BigDecimal STANDARD_UPPER = new BigDecimal("0.25");
    private static final BigDecimal HALF_PPR_UPPER = new BigDecimal("0.75");

    /**
     * Nearest-bucket mapping for custom scoring rules: {@code < 0.25} → STANDARD,
     * {@code < 0.75} → HALF_PPR, else PPR. ADP is only published per named format,
     * so a custom reception value reads the market column it sits closest to.
     * Presets keep using {@link ScoringFormat#adpBucket()}; this is the
     * custom-rules path only.
     */
    public static AdpBucket forReceptionPoints(BigDecimal receptionPoints) {
        if (receptionPoints == null) {
            throw new IllegalArgumentException("receptionPoints must not be null");
        }
        if (receptionPoints.compareTo(STANDARD_UPPER) < 0) {
            return STANDARD;
        }
        if (receptionPoints.compareTo(HALF_PPR_UPPER) < 0) {
            return HALF_PPR;
        }
        return PPR;
    }
}
