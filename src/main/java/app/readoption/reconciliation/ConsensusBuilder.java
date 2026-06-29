package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Pure consensus selection. No model, no Spring — fully unit-testable.
 *
 * <p>Two selection rules over stat lines we already hold:
 * <ul>
 *   <li><b>median</b> — the per-stat median across sources (for n=2 the midpoint).
 *       Every value traces to a median of real source lines.</li>
 *   <li><b>highest / lowest</b> — the single source line with the most / least
 *       measuring-stick points. Every value traces to one real source line.</li>
 * </ul>
 * The model never emits a stat; it only picks which of these rules to apply.
 */
public final class ConsensusBuilder {

    private static final int SCALE = 2;

    private ConsensusBuilder() {
    }

    /** Per-stat median line across all sources; gamesPlayed from the first source that has one. */
    public static ProjectionStatLine median(List<ScoredSource> sources) {
        List<PlayerProjectionRaw> lines = sources.stream().map(ScoredSource::line).toList();
        return ProjectionStatLine.builder()
                .passingYards(medianOf(lines, PlayerProjectionRaw::getPassingYards))
                .passingTd(medianOf(lines, PlayerProjectionRaw::getPassingTd))
                .interceptions(medianOf(lines, PlayerProjectionRaw::getInterceptions))
                .rushingYards(medianOf(lines, PlayerProjectionRaw::getRushingYards))
                .rushingTd(medianOf(lines, PlayerProjectionRaw::getRushingTd))
                .receptions(medianOf(lines, PlayerProjectionRaw::getReceptions))
                .receivingYards(medianOf(lines, PlayerProjectionRaw::getReceivingYards))
                .receivingTd(medianOf(lines, PlayerProjectionRaw::getReceivingTd))
                .fumblesLost(medianOf(lines, PlayerProjectionRaw::getFumblesLost))
                .twoPtConv(medianOf(lines, PlayerProjectionRaw::getTwoPtConv))
                .gamesPlayed(gamesPlayed(lines))
                .build();
    }

    /** The source line with the most measuring-stick points. */
    public static ScoredSource highest(List<ScoredSource> sources) {
        return sources.stream()
                .max(Comparator.comparing(ScoredSource::points))
                .orElseThrow(() -> new IllegalArgumentException("no sources to select from"));
    }

    /** The source line with the fewest measuring-stick points. */
    public static ScoredSource lowest(List<ScoredSource> sources) {
        return sources.stream()
                .min(Comparator.comparing(ScoredSource::points))
                .orElseThrow(() -> new IllegalArgumentException("no sources to select from"));
    }

    /**
     * Median over the present (non-null) values of one stat. A null means a source
     * did not project that stat (e.g. a RB has no passing line), so it is excluded
     * rather than counted as zero. All-null -> null. Even count -> midpoint of the
     * two central values.
     */
    private static BigDecimal medianOf(List<PlayerProjectionRaw> lines,
                                       Function<PlayerProjectionRaw, BigDecimal> stat) {
        List<BigDecimal> values = new ArrayList<>();
        for (PlayerProjectionRaw line : lines) {
            BigDecimal v = stat.apply(line);
            if (v != null) {
                values.add(v);
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        values.sort(Comparator.naturalOrder());
        int n = values.size();
        int mid = n / 2;
        if (n % 2 == 1) {
            return values.get(mid).setScale(SCALE, RoundingMode.HALF_UP);
        }
        return values.get(mid - 1).add(values.get(mid))
                .divide(BigDecimal.valueOf(2), SCALE, RoundingMode.HALF_UP);
    }

    private static Integer gamesPlayed(List<PlayerProjectionRaw> lines) {
        return lines.stream()
                .map(PlayerProjectionRaw::getGamesPlayed)
                .filter(g -> g != null)
                .findFirst()
                .orElse(null);
    }
}
