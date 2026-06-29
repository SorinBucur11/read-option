package app.readoption.reconciliation;

import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The first retrieval seam (RAG increment 1): given a set of player ids and the projection
 * season, return each player's recent actual production from {@code player_stats}. Retrieval
 * here = a SQL query; the augmentation (actuals in the prompt) and generation (the verdict)
 * live downstream. Later retrievers (depth chart, news, ADP) plug into the same shape.
 *
 * <p>Concrete by design: one retriever, one concrete class — the interface gets extracted
 * when retriever #2 arrives, not before.
 *
 * <p><b>Batch, never N+1.</b> {@link #retrieve} issues exactly one query for all ids; the
 * REASON loop reads from the returned map. The alternative — a per-player fetch inside the
 * loop — would interleave up to 128 {@code player_stats} round-trips with 128 model calls.
 */
@Component
public class PriorSeasonContextRetriever {

    /** Last N completed seasons of actuals to inject, most-recent-first. */
    private static final int SEASONS_OF_HISTORY = 3;

    private final PlayerStatsRepository playerStatsRepository;

    public PriorSeasonContextRetriever(PlayerStatsRepository playerStatsRepository) {
        this.playerStatsRepository = playerStatsRepository;
    }

    /**
     * Fetch the last {@value #SEASONS_OF_HISTORY} completed seasons before {@code season}
     * for every id in {@code playerIds}, in one query. Each player's list is most-recent-first
     * and contains only the seasons that exist; a player with no rows is absent from the map,
     * so callers must use {@code getOrDefault(id, List.of())} — an empty list is signal
     * ("none on record"), not an omission.
     */
    public Map<String, List<SeasonActuals>> retrieve(Set<String> playerIds, int season) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        List<Integer> years = List.of(season - 1, season - 2, season - 3);
        List<PlayerStats> rows = playerStatsRepository.findByPlayerIdInAndYearIn(playerIds, years);

        return rows.stream()
                .collect(Collectors.groupingBy(
                        PlayerStats::getPlayerId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> list.stream()
                                        .sorted(Comparator.comparingInt(PlayerStats::getYear).reversed())
                                        .map(PriorSeasonContextRetriever::toSeasonActuals)
                                        .toList())));
    }

    private static SeasonActuals toSeasonActuals(PlayerStats s) {
        return new SeasonActuals(
                s.getYear(), s.getGamesPlayed(),
                s.getPassingYards(), s.getPassingTd(),
                s.getRushingYards(), s.getRushingTd(),
                s.getReceptions(), s.getReceivingYards(), s.getReceivingTd());
    }
}
