package app.readoption.scoring;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculates fantasy football points from raw statistics.
 *
 * Fixed scoring rules (same across all formats):
 *   Passing yards:     0.04 pts/yard (1 pt per 25 yards)
 *   Rushing yards:     0.1  pts/yard (1 pt per 10 yards)
 *   Receiving yards:   0.1  pts/yard (1 pt per 10 yards)
 *   Rushing TD:        6 pts
 *   Receiving TD:      6 pts
 *   Interception:     -1 pt
 *   Fumble lost:      -2 pts
 *   2-point conversion: 2 pts
 *
 * Variable rules (defined by ScoringFormat):
 *   Passing TD:        4 or 6 pts
 *   Reception:         0, 0.5, or 1.0 pts
 */
@Service
public class ScoringService {

    private static final BigDecimal PASS_YARDS_PTS = new BigDecimal("0.04");
    private static final BigDecimal RUSH_YARDS_PTS = new BigDecimal("0.1");
    private static final BigDecimal REC_YARDS_PTS = new BigDecimal("0.1");
    private static final BigDecimal RUSH_TD_PTS = new BigDecimal("6");
    private static final BigDecimal REC_TD_PTS = new BigDecimal("6");
    private static final BigDecimal INT_PTS = new BigDecimal("-2");
    private static final BigDecimal FUMBLE_LOST_PTS = new BigDecimal("-2");
    private static final BigDecimal TWO_PT_CONV_PTS = new BigDecimal("2");

    private static final int SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public ScoringResult calculate(StatLine stats, ScoringFormat format) {
        BigDecimal passTdPts = BigDecimal.valueOf(format.getPassingTdPoints());
        BigDecimal receptionPts = BigDecimal.valueOf(format.getPointsPerReception());

        BigDecimal total = BigDecimal.ZERO
                // Passing
                .add(points(stats.getPassingYards(), PASS_YARDS_PTS))
                .add(points(stats.getPassingTd(), passTdPts))
                .add(points(stats.getInterceptions(), INT_PTS))
                // Rushing
                .add(points(stats.getRushingYards(), RUSH_YARDS_PTS))
                .add(points(stats.getRushingTd(), RUSH_TD_PTS))
                // Receiving
                .add(points(stats.getReceptions(), receptionPts))
                .add(points(stats.getReceivingYards(), REC_YARDS_PTS))
                .add(points(stats.getReceivingTd(), REC_TD_PTS))
                // Turnovers
                .add(points(stats.getFumblesLost(), FUMBLE_LOST_PTS))
                // Bonuses
                .add(points(stats.getTwoPtConv(), TWO_PT_CONV_PTS))
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

    private BigDecimal points(Integer statValue, BigDecimal multiplier) {
        if (statValue == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(statValue).multiply(multiplier);
    }
}