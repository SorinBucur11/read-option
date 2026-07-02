package app.readoption.customization;

/**
 * Closed-enum risk appetite for draft picks: chase ceiling ({@code UPSIDE}), protect
 * floor ({@code FLOOR}), or neither ({@code BALANCED}). {@code null} on
 * {@link DraftTactics} means no stated posture.
 */
public enum RiskPosture {
    UPSIDE,
    FLOOR,
    BALANCED
}
