package app.readoption.scoring;

import java.math.BigDecimal;

/**
 * The reception-scoring axis on its own — points awarded per reception.
 *
 * <p>This is one of the two independent axes the old {@link ScoringFormat} enum
 * bundled into six combined constants (the other being passing-TD points). Phase 3
 * decouples them so an arbitrary user format ("Half-PPR with 6-point passing TDs and
 * a TE premium") can be expressed without enumerating every combination. The LLM
 * parser emits this closed enum as the league's base preset; the resolver overlays
 * the remaining axes onto it.
 *
 * <p>Values use the {@link BigDecimal} String constructor (never the {@code double}
 * constructor) so the points fold into {@link ScoringService}'s exact arithmetic.
 */
public enum ReceptionFormat {

    STANDARD(new BigDecimal("0")),
    HALF_PPR(new BigDecimal("0.5")),
    PPR(new BigDecimal("1.0"));

    private final BigDecimal pointsPerReception;

    ReceptionFormat(BigDecimal pointsPerReception) {
        this.pointsPerReception = pointsPerReception;
    }

    public BigDecimal pointsPerReception() {
        return pointsPerReception;
    }
}
