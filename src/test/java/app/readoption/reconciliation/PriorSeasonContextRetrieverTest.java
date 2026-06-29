package app.readoption.reconciliation;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("PriorSeasonContextRetriever — batched prior-actuals fetch over real Postgres")
class PriorSeasonContextRetrieverTest extends AbstractPostgresTest {

    private static final int SEASON = 2026;

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerStatsRepository playerStatsRepository;

    private PriorSeasonContextRetriever retriever;

    @BeforeEach
    void seed() {
        // Concrete class, wired by hand from the autowired repository (no Spring context for a
        // plain @Component under @DataJpaTest).
        retriever = new PriorSeasonContextRetriever(playerStatsRepository);

        playerRepository.save(player("full", "Three Seasons", "RB"));
        playerRepository.save(player("one", "One Season", "WR"));
        playerRepository.save(player("rookie", "No History", "RB"));

        // "full" has all three prior seasons (2023–2025) plus an out-of-window 2022 that must
        // not appear, and the projection season 2026 itself, which is not a *completed* season.
        playerStatsRepository.save(rushStat("full", 2022, 17, 400, 3));
        playerStatsRepository.save(rushStat("full", 2023, 15, 610, 5));
        playerStatsRepository.save(rushStat("full", 2024, 17, 520, 4));
        playerStatsRepository.save(rushStat("full", 2025, 16, 280, 2));
        playerStatsRepository.save(rushStat("full", 2026, 1, 10, 0));

        // "one" has a single in-window season.
        playerStatsRepository.save(rushStat("one", 2025, 12, 150, 1));
    }

    @Test
    @DisplayName("a player with three prior seasons returns three actuals, most-recent-first")
    void threeSeasonsMostRecentFirst() {
        Map<String, List<SeasonActuals>> result =
                retriever.retrieve(Set.of("full", "one", "rookie"), SEASON);

        List<SeasonActuals> full = result.get("full");
        assertThat(full).extracting(SeasonActuals::year).containsExactly(2025, 2024, 2023);
        assertThat(full.get(0).rushingYards()).isEqualTo(280);
        assertThat(full.get(0).gamesPlayed()).isEqualTo(16);
    }

    @Test
    @DisplayName("seasons outside the 3-year window (and the projection season) are excluded")
    void windowExcludesOutOfRangeSeasons() {
        Map<String, List<SeasonActuals>> result = retriever.retrieve(Set.of("full"), SEASON);

        assertThat(result.get("full")).extracting(SeasonActuals::year)
                .containsExactly(2025, 2024, 2023)
                .doesNotContain(2022, 2026);
    }

    @Test
    @DisplayName("a player with one prior season returns one actual")
    void oneSeason() {
        Map<String, List<SeasonActuals>> result = retriever.retrieve(Set.of("one"), SEASON);

        assertThat(result.get("one")).extracting(SeasonActuals::year).containsExactly(2025);
    }

    @Test
    @DisplayName("a player with no history is absent from the map (empty list via getOrDefault)")
    void noHistoryAbsentFromMap() {
        Map<String, List<SeasonActuals>> result =
                retriever.retrieve(Set.of("full", "one", "rookie"), SEASON);

        assertThat(result).doesNotContainKey("rookie");
        assertThat(result.getOrDefault("rookie", List.of())).isEmpty();
    }

    @Test
    @DisplayName("an empty id set short-circuits to an empty map")
    void emptyIdSet() {
        assertThat(retriever.retrieve(Set.of(), SEASON)).isEmpty();
    }

    private static PlayerStats rushStat(String playerId, int year, int gamesPlayed,
                                        int rushingYards, int rushingTd) {
        return PlayerStats.builder()
                .playerId(playerId)
                .year(year)
                .games(17)
                .gamesPlayed(gamesPlayed)
                .rushingYards(rushingYards)
                .rushingTd(rushingTd)
                .build();
    }
}
