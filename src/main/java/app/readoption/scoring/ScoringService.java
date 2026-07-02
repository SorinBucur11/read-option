package app.readoption.scoring;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates fantasy football points from raw statistics.
 *
 * <p>Every per-stat multiplier comes from the resolved {@link ScoringRules} handed in —
 * there are no scoring constants left in this class. A {@link ScoringFormat} preset
 * resolves to its rules via {@link ScoringFormat#toScoringRules()}; a user-customized
 * league resolves to its rules in the Phase 3 customization resolver. Either way the
 * service is agnostic about where the rules came from.
 *
 * <p>{@link Position} is threaded in only so a position-dependent rule — currently the
 * TE reception bonus — can apply to the right players. {@link StatLine} stays pure (no
 * position on the stat contract); the caller, which already knows the player, passes it.
 * A {@code null} position simply means no position-dependent rule fires, which is the
 * case for all six presets (they carry {@link ScoringRules#NO_TE_BONUS}).
 */
@Service
public class ScoringService {

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public ScoringResult calculate(StatLine stats, ScoringRules rules, Position position) {
        BigDecimal receptionPts = rules.pointsPerReception();
        // TE premium: tight ends earn extra per reception. Applies to TEs only, and is
        // zero for every preset, so this branch never moves a preset's number.
        if (position == Position.TE) {
            receptionPts = receptionPts.add(rules.teReceptionBonus());
        }

        BigDecimal total = BigDecimal.ZERO
                // Passing
                .add(points(stats.getPassingYards(), rules.passingYardPoints()))
                .add(points(stats.getPassingTd(), rules.passingTdPoints()))
                .add(points(stats.getInterceptions(), rules.interceptionPoints()))
                // Rushing
                .add(points(stats.getRushingYards(), rules.rushingYardPoints()))
                .add(points(stats.getRushingTd(), rules.rushingTdPoints()))
                // Receiving
                .add(points(stats.getReceptions(), receptionPts))
                .add(points(stats.getReceivingYards(), rules.receivingYardPoints()))
                .add(points(stats.getReceivingTd(), rules.receivingTdPoints()))
                // Turnovers
                .add(points(stats.getFumblesLost(), rules.fumbleLostPoints()))
                // Bonuses
                .add(points(stats.getTwoPtConv(), rules.twoPtConvPoints()))
                .setScale(SCALE, ROUNDING);

        BigDecimal ppg = calculatePpg(total, stats.getGamesPlayed());

        return new ScoringResult(total, ppg);
    }

    private BigDecimal calculatePpg(BigDecimal totalPoints, Integer gamesPlayed) {
        if (gamesPlayed == null || gamesPlayed <= 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return totalPoints.divide(BigDecimal.valueOf(gamesPlayed), SCALE, ROUNDING);
    }

    private BigDecimal points(Number statValue, BigDecimal multiplier) {
        if (statValue == null) {
            return BigDecimal.ZERO;
        }
        // Narrow to BigDecimal without going through double, so a fractional
        // projection (e.g. 250.70 receiving yards) keeps its exact value.
        BigDecimal value = statValue instanceof BigDecimal bd
                ? bd
                : new BigDecimal(statValue.toString());
        return value.multiply(multiplier);
    }
}
