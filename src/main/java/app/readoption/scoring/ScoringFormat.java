package app.readoption.scoring;

import java.math.BigDecimal;

/**
 * The six named scoring presets — each a combination of a {@link ReceptionFormat}
 * axis and a passing-TD value.
 *
 * <p>Phase 3 graduated the per-stat rules into {@link ScoringRules}; this enum is now
 * a <b>preset registry</b> that maps each named format to a resolved {@code ScoringRules}
 * via {@link #toScoringRules()}. The presets carry no TE premium and use the engine's
 * default interception value, so {@code toScoringRules()} reproduces exactly the numbers
 * the old combined enum produced — the regression anchors must not move.
 *
 * <p>The enum is still the persisted key on {@code player_scoring} (one scored row per
 * format), which is why it remains a closed enum rather than dissolving entirely into
 * {@code ScoringRules}.
 */
public enum ScoringFormat {

    STANDARD_4PT("Standard", ReceptionFormat.STANDARD, 4),
    STANDARD_6PT("Standard (6pt Pass TD)", ReceptionFormat.STANDARD, 6),
    HALF_PPR_4PT("Half PPR", ReceptionFormat.HALF_PPR, 4),
    HALF_PPR_6PT("Half PPR (6pt Pass TD)", ReceptionFormat.HALF_PPR, 6),
    PPR_4PT("PPR", ReceptionFormat.PPR, 4),
    PPR_6PT("PPR (6pt Pass TD)", ReceptionFormat.PPR, 6);

    private final String displayName;
    private final ReceptionFormat receptionFormat;
    private final int passingTdPoints;

    ScoringFormat(String displayName, ReceptionFormat receptionFormat, int passingTdPoints) {
        this.displayName = displayName;
        this.receptionFormat = receptionFormat;
        this.passingTdPoints = passingTdPoints;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Resolve this preset to the engine's scoring config: the reception axis plus the
     * passing-TD value, with the engine's default interception rule and no TE premium.
     */
    public ScoringRules toScoringRules() {
        return ScoringRules.of(
                receptionFormat.pointsPerReception(),
                new BigDecimal(passingTdPoints),
                ScoringRules.DEFAULT_INTERCEPTION_POINTS,
                ScoringRules.NO_TE_BONUS
        );
    }

    public AdpBucket adpBucket() {
        return switch (this) {
            case STANDARD_4PT, STANDARD_6PT -> AdpBucket.STANDARD;
            case HALF_PPR_4PT, HALF_PPR_6PT -> AdpBucket.HALF_PPR;
            case PPR_4PT, PPR_6PT -> AdpBucket.PPR;
        };
    }
}
