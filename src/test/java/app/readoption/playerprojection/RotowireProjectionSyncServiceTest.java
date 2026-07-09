package app.readoption.playerprojection;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperProjection;
import app.readoption.sleeper.SleeperProjectionData;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RotowireProjectionSyncService — Sleeper(rotowire) lands in raw")
class RotowireProjectionSyncServiceTest {

    @Mock private SleeperClient sleeperClient;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerProjectionRawRepository rawRepository;

    private RotowireProjectionSyncService service;
    private final List<PlayerProjectionRaw> captured = new ArrayList<>();

    @BeforeEach
    void setUp() {
        captured.clear();
        service = new RotowireProjectionSyncService(
                sleeperClient, playerRepository, rawRepository,
                new RotowireProjectionMapper(new JsonMapper()));
        when(rawRepository.findPlayerIdsByYearAndSource(anyInt(), anyString())).thenReturn(Set.of());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Iterable<PlayerProjectionRaw> saved = (Iterable<PlayerProjectionRaw>) inv.getArgument(0);
            saved.forEach(captured::add);
            return List.of();
        }).when(rawRepository).saveAll(any());
    }

    private static SleeperProjectionData projData(Double... v) {
        return new SleeperProjectionData(
                get(v, 0), get(v, 1), get(v, 2), get(v, 3), get(v, 4), get(v, 5),
                get(v, 6), get(v, 7), get(v, 8), get(v, 9), get(v, 10), get(v, 11),
                get(v, 12), get(v, 13), get(v, 14), get(v, 15), get(v, 16), get(v, 17));
    }

    private static Double get(Double[] arr, int idx) {
        return idx < arr.length ? arr[idx] : null;
    }

    private static SleeperProjection proj(String playerId, SleeperProjectionData data) {
        return new SleeperProjection(playerId, "KC", "rotowire", data);
    }

    @Test
    @DisplayName("unknown player ids, null ids, and null stats are filtered out")
    void filtersUnknownAndEmpty() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("KNOWN", projData()),
                proj("UNKNOWN", projData()),
                proj(null, projData()),
                proj("KNOWN", null)));
        when(playerRepository.findAll()).thenReturn(List.of(Player.builder().id("KNOWN").build()));

        int count = service.sync(2026);

        assertThat(count).isEqualTo(1);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).getPlayerId()).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("source is rotowire and gamesPlayed defaults to 17")
    void sourceAndGames() {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(proj("P1", projData())));
        when(playerRepository.findAll()).thenReturn(List.of(Player.builder().id("P1").build()));

        service.sync(2026);

        assertThat(captured.get(0).getSource()).isEqualTo("rotowire");
        assertThat(captured.get(0).getGamesPlayed()).isEqualTo(17);
    }

    @Test
    @DisplayName("fractional projections are preserved (NUMERIC), not rounded to int")
    void preservesFractional() {
        // passingYards=250.7 at index 0
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(proj("P1", projData(250.7))));
        when(playerRepository.findAll()).thenReturn(List.of(Player.builder().id("P1").build()));

        service.sync(2026);

        assertThat(captured.get(0).getPassingYards()).isEqualByComparingTo("250.70");
    }

    @Test
    @DisplayName("all three per-format ADPs land; the 999 sentinel becomes null per field")
    void adpPerFormat() {
        // adp_std=12, adp_half_ppr=13, adp_ppr=14
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(
                proj("UNRANKED", projData(null, null, null, null, null, null, null, null,
                        null, null, null, null, 999.0, 999.0, 999.0)),
                proj("RANKED", projData(null, null, null, null, null, null, null, null,
                        null, null, null, null, 10.2, 9.1, 8.5))));
        when(playerRepository.findAll()).thenReturn(List.of(
                Player.builder().id("UNRANKED").build(), Player.builder().id("RANKED").build()));

        service.sync(2026);

        PlayerProjectionRaw unranked = captured.stream()
                .filter(r -> r.getPlayerId().equals("UNRANKED")).findFirst().orElseThrow();
        PlayerProjectionRaw ranked = captured.stream()
                .filter(r -> r.getPlayerId().equals("RANKED")).findFirst().orElseThrow();
        assertThat(unranked.getAdpStd()).isNull();
        assertThat(unranked.getAdpHalfPpr()).isNull();
        assertThat(unranked.getAdpPpr()).isNull();
        assertThat(ranked.getAdpStd()).isEqualByComparingTo("10.2");
        assertThat(ranked.getAdpHalfPpr()).isEqualByComparingTo("9.1");
        assertThat(ranked.getAdpPpr()).isEqualByComparingTo("8.5");
    }

    @Test
    @DisplayName("source_payload is non-null and round-trips to a SleeperProjection matching the row")
    void sourcePayloadRoundTrips() throws Exception {
        when(sleeperClient.fetchProjections(2026)).thenReturn(List.of(proj("P1", projData(250.7))));
        when(playerRepository.findAll()).thenReturn(List.of(Player.builder().id("P1").build()));

        service.sync(2026);

        String payload = captured.get(0).getSourcePayload();
        assertThat(payload).isNotNull();
        SleeperProjection roundTripped = new JsonMapper().readValue(payload, SleeperProjection.class);
        assertThat(roundTripped.playerId()).isEqualTo(captured.get(0).getPlayerId());
    }
}
