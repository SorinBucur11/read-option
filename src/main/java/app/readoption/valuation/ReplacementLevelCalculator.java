package app.readoption.valuation;

import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure replacement-level math — no Spring, no I/O (the {@code DispersionCalculator}
 * idiom). Input is already-scored players; output is the points of the best player
 * <i>outside</i> each position's startable pool. VORP = points − replacement level.
 *
 * <p>Deterministic greedy absorption: dedicated starters first, then flex over the
 * flex-eligible set, then superflex with QB added to the eligible set. Bench slots
 * do <b>not</b> extend the baseline — starters-only definition; bench value is
 * judgment, left to the LLM later.
 */
public final class ReplacementLevelCalculator {

    private static final Logger log = LoggerFactory.getLogger(ReplacementLevelCalculator.class);

    /** Points desc, tie-break playerId asc — fully deterministic ordering. */
    private static final Comparator<PlayerValue> BY_POINTS_DESC =
            Comparator.comparing(PlayerValue::points).reversed()
                    .thenComparing(PlayerValue::playerId);

    private ReplacementLevelCalculator() {
    }

    /**
     * Replacement level per position present in {@code players}. A position so
     * shallow that every listed player is startable gets a {@link BigDecimal#ZERO}
     * baseline (WARN, never a throw).
     */
    public static Map<Position, BigDecimal> replacementLevels(List<PlayerValue> players,
                                                              int teamCount,
                                                              LeagueSettings settings) {
        Map<Position, List<PlayerValue>> byPosition = new EnumMap<>(Position.class);
        for (PlayerValue player : players) {
            byPosition.computeIfAbsent(player.position(), p -> new ArrayList<>()).add(player);
        }
        byPosition.values().forEach(list -> list.sort(BY_POINTS_DESC));

        // 1) dedicated starters: reserve the top teamCount x starters(position)
        Map<Position, Integer> reserved = new EnumMap<>(Position.class);
        for (Position position : byPosition.keySet()) {
            reserved.put(position, teamCount * dedicatedStarters(position, settings));
        }

        // 2) flex absorption, then 3) superflex (QB joins the eligible set)
        absorb(byPosition, reserved, settings.flexEligible(),
                teamCount * settings.flexSlots());
        Set<Position> superflexEligible = EnumSet.noneOf(Position.class);
        superflexEligible.addAll(settings.flexEligible());
        superflexEligible.add(Position.QB);
        absorb(byPosition, reserved, superflexEligible,
                teamCount * settings.superflexSlots());

        // 4) replacement level = the best player outside the startable pool
        Map<Position, BigDecimal> levels = new EnumMap<>(Position.class);
        for (Map.Entry<Position, List<PlayerValue>> entry : byPosition.entrySet()) {
            Position position = entry.getKey();
            List<PlayerValue> sorted = entry.getValue();
            int index = reserved.get(position);
            if (index >= sorted.size()) {
                log.warn("Position {} has only {} scored players for {} startable slots — "
                        + "replacement level falls to ZERO", position, sorted.size(), index);
                levels.put(position, BigDecimal.ZERO);
            } else {
                levels.put(position, sorted.get(index).points());
            }
        }
        return levels;
    }

    /**
     * One greedy absorption pass: pool every still-unreserved player whose position
     * is eligible, take the top {@code slots} by points, and grow each position's
     * reserved count by how many of its players landed. 0 slots → no-op.
     */
    private static void absorb(Map<Position, List<PlayerValue>> byPosition,
                               Map<Position, Integer> reserved,
                               Set<Position> eligible,
                               int slots) {
        if (slots <= 0) {
            return;
        }
        List<PlayerValue> pool = new ArrayList<>();
        for (Map.Entry<Position, List<PlayerValue>> entry : byPosition.entrySet()) {
            if (!eligible.contains(entry.getKey())) {
                continue;
            }
            List<PlayerValue> sorted = entry.getValue();
            int from = Math.min(reserved.get(entry.getKey()), sorted.size());
            pool.addAll(sorted.subList(from, sorted.size()));
        }
        pool.sort(BY_POINTS_DESC);

        Map<Position, Integer> absorbed = new HashMap<>();
        pool.stream()
                .limit(slots)
                .forEach(player -> absorbed.merge(player.position(), 1, Integer::sum));
        absorbed.forEach((position, count) -> reserved.merge(position, count, Integer::sum));
    }

    private static int dedicatedStarters(Position position, LeagueSettings settings) {
        return switch (position) {
            case QB -> settings.qbSlots();
            case RB -> settings.rbSlots();
            case WR -> settings.wrSlots();
            case TE -> settings.teSlots();
            case K, DEF -> 0;   // out of scope for v1 — no dedicated baseline
        };
    }
}
