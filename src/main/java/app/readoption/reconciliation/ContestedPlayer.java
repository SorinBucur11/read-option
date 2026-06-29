package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;

import java.math.BigDecimal;
import java.util.List;

/**
 * Everything the model needs to classify one contested player — built by the engine,
 * passed to {@link VerdictClassifier}. Carries the <b>per-stat breakdown</b> of the
 * disagreement (not just two point totals) so the model can reason about the shape of
 * the gap: touchdown-driven gaps regress, volume/role gaps are structural.
 *
 * <p>Also carries {@code priorActuals} — the player's recent actual production, retrieved
 * batched during READ and used as the baseline the verdict is judged against. An empty list
 * is signal ("none on record" — rookie or no prior NFL stats), not a missing field.
 */
public record ContestedPlayer(
        String name,
        String position,
        String team,
        String measuringStick,          // e.g. "PPR" — labels the scored points
        List<Source> sources,
        String highSource,
        BigDecimal highPoints,
        String lowSource,
        BigDecimal lowPoints,
        List<SeasonActuals> priorActuals
) {

    /** One source's line and its measuring-stick points. */
    public record Source(String name, BigDecimal points, PlayerProjectionRaw line) {
    }
}
