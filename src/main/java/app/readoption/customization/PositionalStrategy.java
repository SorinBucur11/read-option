package app.readoption.customization;

/**
 * Closed-enum draft-construction leans the parser may extract from a stated
 * preference. {@code null} on {@link DraftTactics} means no lean was stated —
 * the parser never assigns one the user didn't voice.
 */
public enum PositionalStrategy {
    ZERO_RB,
    HERO_RB,
    ROBUST_RB,
    BALANCED,
    BEST_AVAILABLE
}
