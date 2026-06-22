package app.readoption.playerprojection;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperProjection;
import app.readoption.sleeper.SleeperProjectionData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerProjectionSyncService — ETL cleansing")
class PlayerProjectionSyncServiceTest {

    @Mock private SleeperClient sleeperClient;
    @Mock private PlayerProjectionRepository playerProjectionRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerScoringService playerScoringService;

    private PlayerProjectionSyncService service;
    private final List<PlayerProjection> capturedProjections = new ArrayList<>();

    @BeforeEach
    void setUp() {
        capturedProjections.clear();
        service = new PlayerProjectionSyncService(
                sleeperClient, playerProjectionRepository, playerRepository, playerScoringService);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<PlayerProjection> saved = (Iterable<PlayerProjection>) inv.getArgument(0);
            saved.forEach(capturedProjections::add);
            return List.of();
        }).when(playerProjectionRepository).saveAll(any());
    }

    private static Player player(String id) {
        return Player.builder().id(id).build();
    }

    // 18 fields in constructor order: passYd, passTd, int, rushYd, rushTd, rec, recYd, recTd,
    // fumLost, pass2pt, rush2pt, rec2pt, adpStd, adpHalfPpr, adpPpr, ptsStd, ptsPpr, ptsPpr
    private static SleeperProjectionData projData(Double... values) {
        return new SleeperProjectionData(
                get(values, 0), get(values, 1), get(values, 2), get(values, 3), get(values, 4),
                get(values, 5), get(values, 6), get(values, 7), get(values, 8), get(values, 9),
                get(values, 10), get(values, 11), get(values, 12), get(values, 13), get(values, 14),
                get(values, 15), get(values, 16), get(values, 17));
    }

    private static Double get(Double[] arr, int idx) {
        return idx < arr.length ? arr[idx] : null;
    }

    private SleeperProjection proj(String playerId, String company, SleeperProjectionData data) {
        return new SleeperProjection(playerId, "KC", company, data);
    }

    @Test
    @DisplayName("projections with unknown player IDs are filtered out")
    void filtersOutUnknownPlayers() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("KNOWN", "rotowire", projData()),
                proj("UNKNOWN", "rotowire", projData())));
        when(playerRepository.findAll()).thenReturn(List.of(player("KNOWN")));

        int result = service.syncProjections(2026);

        assertThat(result).isEqualTo(1);
        assertThat(capturedProjections.get(0).getPlayerId()).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("ADP sentinel 999 becomes null (player is unranked)")
    void adp999BecomesNull() {
        // adpStd is at position index 12 (13th field)
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", projData(null, null, null, null, null,
                        null, null, null, null, null, null, null, 999.0))));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getAdpStd()).isNull();
    }

    @Test
    @DisplayName("null ADP stays null")
    void adpNullStaysNull() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", projData())));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getAdpStd()).isNull();
    }

    @Test
    @DisplayName("valid ADP converts to BigDecimal without binary-float artifacts")
    void validAdpConvertsCorrectly() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", projData(null, null, null, null, null,
                        null, null, null, null, null, null, null, 5.5))));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getAdpStd())
                .isEqualByComparingTo(BigDecimal.valueOf(5.5));
    }

    @Test
    @DisplayName("gamesPlayed is always 17 regardless of projection source data")
    void gamesPlayedAlways17() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", projData())));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getGamesPlayed()).isEqualTo(17);
    }

    @Test
    @DisplayName("source field is taken from the SleeperProjection company field")
    void sourceFromCompany() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", projData())));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getSource()).isEqualTo("rotowire");
    }

    @Test
    @DisplayName("source defaults to 'unknown' when company is null")
    void sourceDefaultsToUnknownWhenCompanyNull() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", null, projData())));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        assertThat(capturedProjections.get(0).getSource()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("Double -> Integer: API doubles are truncated to integers in the entity")
    void doublesConvertToIntegers() {
        // passingYards=250.7 -> 250, passingTd=2.0 -> 2
        SleeperProjectionData data = projData(250.7, 2.0);
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("P1", "rotowire", data)));
        when(playerRepository.findAll()).thenReturn(List.of(player("P1")));

        service.syncProjections(2026);

        assertThat(capturedProjections).hasSize(1);
        PlayerProjection entity = capturedProjections.get(0);
        assertThat(entity.getPassingYards()).isEqualTo(250);
        assertThat(entity.getPassingTd()).isEqualTo(2);
    }
}
