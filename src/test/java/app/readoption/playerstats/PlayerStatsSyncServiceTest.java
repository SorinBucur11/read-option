package app.readoption.playerstats;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperPlayerStats;
import app.readoption.sleeper.SleeperStatsData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerStatsSyncService — ETL cleansing")
class PlayerStatsSyncServiceTest {

    @Mock private SleeperClient sleeperClient;
    @Mock private PlayerStatsRepository playerStatsRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerScoringService playerScoringService;

    private PlayerStatsSyncService service;
    private final List<PlayerStats> capturedStats = new ArrayList<>();

    @BeforeEach
    void setUp() {
        capturedStats.clear();
        service = new PlayerStatsSyncService(
                sleeperClient, playerStatsRepository, playerRepository, playerScoringService);
        // Capture whatever is passed to saveAll so tests can inspect the resulting entities.
        // @BeforeEach stubs are lenient in STRICT_STUBS mode.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<PlayerStats> saved = (Iterable<PlayerStats>) inv.getArgument(0);
            saved.forEach(capturedStats::add);
            return List.of();
        }).when(playerStatsRepository).saveAll(any());
    }

    private static Player knownPlayer(String id) {
        return Player.builder().id(id).build();
    }

    // 17 fields in constructor order: gp, passAtt, passCmp, passYd, passTd, int,
    // rushAtt, rushYd, rushTd, tgt, rec, recYd, recTd, fumLost, pass2pt, rush2pt, rec2pt
    private static SleeperStatsData stats(Double... values) {
        return new SleeperStatsData(
                get(values, 0), get(values, 1), get(values, 2), get(values, 3), get(values, 4),
                get(values, 5), get(values, 6), get(values, 7), get(values, 8), get(values, 9),
                get(values, 10), get(values, 11), get(values, 12), get(values, 13), get(values, 14),
                get(values, 15), get(values, 16));
    }

    private static Double get(Double[] arr, int idx) {
        return idx < arr.length ? arr[idx] : null;
    }

    @Test
    @DisplayName("stats with an unknown player ID are filtered out")
    void filtersOutUnknownPlayers() {
        when(sleeperClient.fetchStats(2025)).thenReturn(List.of(
                new SleeperPlayerStats("KNOWN", "KC", "2025", stats()),
                new SleeperPlayerStats("UNKNOWN", "LAC", "2025", stats())));
        when(playerRepository.findAll()).thenReturn(List.of(knownPlayer("KNOWN")));

        int result = service.syncStats(2025);

        assertThat(result).isEqualTo(1);
        assertThat(capturedStats).hasSize(1);
        assertThat(capturedStats.get(0).getPlayerId()).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("stats with null playerId are filtered out")
    void filtersOutNullPlayerId() {
        when(sleeperClient.fetchStats(2025)).thenReturn(List.of(
                new SleeperPlayerStats(null, "KC", "2025", stats())));
        when(playerRepository.findAll()).thenReturn(List.of());

        assertThat(service.syncStats(2025)).isZero();
        assertThat(capturedStats).isEmpty();
    }

    @Test
    @DisplayName("stats with null stats data are filtered out even when player ID is known")
    void filtersOutNullStatsData() {
        when(sleeperClient.fetchStats(2025)).thenReturn(List.of(
                new SleeperPlayerStats("P1", "KC", "2025", null)));
        when(playerRepository.findAll()).thenReturn(List.of(knownPlayer("P1")));

        assertThat(service.syncStats(2025)).isZero();
        assertThat(capturedStats).isEmpty();
    }

    @Test
    @DisplayName("Double -> Integer: API doubles are truncated to integers in the entity")
    void doublesConvertToIntegers() {
        // gp=17.0, passingYards=300.0, passingTd=3.0 at positions 0,3,4
        SleeperStatsData data = stats(17.0, null, null, 300.0, 3.0);
        when(sleeperClient.fetchStats(2025)).thenReturn(List.of(
                new SleeperPlayerStats("P1", "KC", "2025", data)));
        when(playerRepository.findAll()).thenReturn(List.of(knownPlayer("P1")));

        service.syncStats(2025);

        assertThat(capturedStats).hasSize(1);
        PlayerStats entity = capturedStats.get(0);
        assertThat(entity.getGamesPlayed()).isEqualTo(17);
        assertThat(entity.getPassingYards()).isEqualTo(300);
        assertThat(entity.getPassingTd()).isEqualTo(3);
    }

    @Test
    @DisplayName("games field is set to the season total (17 for 2021+, not the raw API gp)")
    void gamesIsSeasonTotalForModernSeason() {
        // API reports gp=18.0 (data glitch); games field must still be season total = 17
        when(sleeperClient.fetchStats(2025)).thenReturn(List.of(
                new SleeperPlayerStats("P1", "KC", "2025", stats(18.0))));
        when(playerRepository.findAll()).thenReturn(List.of(knownPlayer("P1")));

        service.syncStats(2025);

        assertThat(capturedStats).hasSize(1);
        assertThat(capturedStats.get(0).getGames()).isEqualTo(17);
        // gamesPlayed IS the raw API value (not capped); scoring uses this field
        assertThat(capturedStats.get(0).getGamesPlayed()).isEqualTo(18);
    }

    @Test
    @DisplayName("games field is 16 for pre-2021 seasons (16-game schedule)")
    void gamesIs16ForOldSeason() {
        when(sleeperClient.fetchStats(2019)).thenReturn(List.of(
                new SleeperPlayerStats("P1", "KC", "2019", stats())));
        when(playerRepository.findAll()).thenReturn(List.of(knownPlayer("P1")));

        service.syncStats(2019);

        assertThat(capturedStats).hasSize(1);
        assertThat(capturedStats.get(0).getGames()).isEqualTo(16);
    }
}
