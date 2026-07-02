package app.readoption.scoring;

import java.math.BigDecimal;

/**
 * The engine's <b>resolved</b> scoring config — every per-stat multiplier the
 * {@link ScoringService} needs, fully materialized as {@link BigDecimal}s.
 *
 * <p>This is the value object the old {@link ScoringFormat} enum graduates into.
 * Where the enum hard-coded six combined constants and the remaining rules lived as
 * private constants inside {@code ScoringService}, those rules now all live here, on
 * one immutable object the service consumes. That decoupling is what lets a league
 * vary the reception axis, the passing-TD axis, the interception value, and a
 * TE-only reception bonus independently.
 *
 * <p><b>Boundary note (Phase 3):</b> the LLM never emits this type. It emits a narrow
 * spec (a preset + a few extracted numbers + flags); a deterministic resolver maps
 * that spec onto a {@code ScoringRules}. The TE-premium number, in particular, lives
 * only in the resolver's registry — the model has no field to write it into.
 *
 * <p>All arithmetic stays on the {@link BigDecimal} String-constructor path
 * ({@link #STANDARD_PASS_YARD_POINTS} et al.), never the {@code double} constructor.
 */
public record ScoringRules(
        BigDecimal passingYardPoints,
        BigDecimal rushingYardPoints,
        BigDecimal receivingYardPoints,
        BigDecimal passingTdPoints,
        BigDecimal rushingTdPoints,
        BigDecimal receivingTdPoints,
        BigDecimal pointsPerReception,
        BigDecimal interceptionPoints,
        BigDecimal fumbleLostPoints,
        BigDecimal twoPtConvPoints,
        BigDecimal teReceptionBonus
) {

    /**
     * Rules that are fixed across every standard preset — they were the private
     * {@code ScoringService} constants before this graduation. The preset registry and
     * the Phase 3 resolver both reuse these so only the genuinely variable axes
     * (reception, passing TD, interception, TE bonus) are ever specified at a call site.
     */
    public static final BigDecimal STANDARD_PASS_YARD_POINTS = new BigDecimal("0.04");
    public static final BigDecimal STANDARD_RUSH_YARD_POINTS = new BigDecimal("0.1");
    public static final BigDecimal STANDARD_REC_YARD_POINTS = new BigDecimal("0.1");
    public static final BigDecimal STANDARD_RUSH_TD_POINTS = new BigDecimal("6");
    public static final BigDecimal STANDARD_REC_TD_POINTS = new BigDecimal("6");
    public static final BigDecimal STANDARD_FUMBLE_LOST_POINTS = new BigDecimal("-2");
    public static final BigDecimal STANDARD_TWO_PT_CONV_POINTS = new BigDecimal("2");

    /**
     * League-rule default for interceptions: −2 per INT. This is the engine's rule and
     * the regression anchor (Mahomes is scored at −2/INT, not the −1 a provider's own
     * point total would use). The reconciliation measuring stick relies on this too.
     */
    public static final BigDecimal DEFAULT_INTERCEPTION_POINTS = new BigDecimal("-2");

    /** No position-dependent reception bonus — the value the six named presets carry. */
    public static final BigDecimal NO_TE_BONUS = new BigDecimal("0");

    /**
     * Build a full rule set from only the variable axes, filling the fixed rules with
     * the standard constants. The preset registry and the resolver both go through here.
     *
     * @param pointsPerReception base reception value (the {@link ReceptionFormat} axis)
     * @param passingTdPoints    points per passing TD (4 or 6 in the presets)
     * @param interceptionPoints points per interception
     * @param teReceptionBonus   extra points per reception for TEs only (0 = no premium)
     */
    public static ScoringRules of(BigDecimal pointsPerReception,
                                  BigDecimal passingTdPoints,
                                  BigDecimal interceptionPoints,
                                  BigDecimal teReceptionBonus) {
        return new ScoringRules(
                STANDARD_PASS_YARD_POINTS,
                STANDARD_RUSH_YARD_POINTS,
                STANDARD_REC_YARD_POINTS,
                passingTdPoints,
                STANDARD_RUSH_TD_POINTS,
                STANDARD_REC_TD_POINTS,
                pointsPerReception,
                interceptionPoints,
                STANDARD_FUMBLE_LOST_POINTS,
                STANDARD_TWO_PT_CONV_POINTS,
                teReceptionBonus
        );
    }
}
