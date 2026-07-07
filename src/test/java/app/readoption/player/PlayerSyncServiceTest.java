package app.readoption.player;

import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperPlayer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mapper coverage for the Phase 4.3 depth-chart/injury landing. Fixtures are
 * blob-shaped JSON deserialized exactly as {@link SleeperClient} does (unknown
 * properties ignored), so the {@code @JsonProperty} wiring is under test too —
 * not just the entity builder. The Mahomes-shape fixture carries a populated
 * {@code team_abbr} that DIFFERS from {@code team}: reading the wrong source
 * field fails the assertion instead of passing silently.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerSyncService — Sleeper blob to player row, raw vocabulary landed as-is")
class PlayerSyncServiceTest {

    /** Same lenient posture as SleeperClient's mapper. */
    private final ObjectMapper blobMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Mock private SleeperClient sleeperClient;
    @Mock private PlayerRepository playerRepository;

    /** Starter, injured — all five context fields populated. */
    private static final String MAHOMES_SHAPE = """
            {
              "player_id": "4046",
              "first_name": "Patrick",
              "last_name": "Mahomes",
              "full_name": "Patrick Mahomes",
              "position": "QB",
              "team": "KC",
              "team_abbr": "WRONG",
              "age": 30,
              "years_exp": 9,
              "status": "Active",
              "active": true,
              "depth_chart_position": "QB",
              "depth_chart_order": 1,
              "injury_status": "Questionable",
              "injury_body_part": "Ankle",
              "injury_notes": "Surgery"
            }
            """;

    /**
     * Healthy-backup shape (invented data — not the real player 11435's row; don't
     * "correct" it to match the ID) — depth fields populated, injury trio null.
     */
    private static final String WILSON_SHAPE = """
            {
              "player_id": "11435",
              "first_name": "Michael",
              "last_name": "Wilson",
              "full_name": "Michael Wilson",
              "position": "WR",
              "team": "ARI",
              "age": 26,
              "years_exp": 3,
              "status": "Active",
              "active": true,
              "depth_chart_position": "LWR",
              "depth_chart_order": 2,
              "injury_status": null,
              "injury_body_part": null,
              "injury_notes": null
            }
            """;

    private Player syncOne(String blobJson) throws Exception {
        SleeperPlayer sleeperPlayer = blobMapper.readValue(blobJson, SleeperPlayer.class);
        when(sleeperClient.fetchAllPlayers())
                .thenReturn(Map.of(sleeperPlayer.playerId(), sleeperPlayer));
        when(playerRepository.findAll()).thenReturn(List.of());

        new PlayerSyncService(sleeperClient, playerRepository).syncPlayers();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Player>> captor = ArgumentCaptor.forClass(List.class);
        verify(playerRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        return captor.getValue().get(0);
    }

    @Test
    @DisplayName("Mahomes-shape: all five context fields land, team read from 'team' not 'team_abbr'")
    void injuredStarterLandsAllContextFields() throws Exception {
        Player player = syncOne(MAHOMES_SHAPE);

        assertThat(player.getId()).isEqualTo("4046");
        assertThat(player.getFullName()).isEqualTo("Patrick Mahomes");
        assertThat(player.getPosition()).isEqualTo("QB");
        assertThat(player.getTeam()).isEqualTo("KC");
        assertThat(player.getDepthChartPosition()).isEqualTo("QB");
        assertThat(player.getDepthChartOrder()).isEqualTo(1);
        assertThat(player.getInjuryStatus()).isEqualTo("Questionable");
        assertThat(player.getInjuryBodyPart()).isEqualTo("Ankle");
        assertThat(player.getInjuryNotes()).isEqualTo("Surgery");
    }

    @Test
    @DisplayName("Wilson-shape: raw sub-position (LWR) lands untranslated, injury trio stays null")
    void healthyBackupLandsDepthOnly() throws Exception {
        Player player = syncOne(WILSON_SHAPE);

        assertThat(player.getId()).isEqualTo("11435");
        assertThat(player.getTeam()).isEqualTo("ARI");
        assertThat(player.getDepthChartPosition()).isEqualTo("LWR");   // raw, never normalized to WR
        assertThat(player.getDepthChartOrder()).isEqualTo(2);
        assertThat(player.getInjuryStatus()).isNull();
        assertThat(player.getInjuryBodyPart()).isNull();
        assertThat(player.getInjuryNotes()).isNull();
    }

    @Test
    @DisplayName("free-agent shape: null team and all context fields null — sync survives")
    void freeAgentAllNullsTolerated() throws Exception {
        Player player = syncOne("""
                {
                  "player_id": "9999",
                  "first_name": "Free",
                  "last_name": "Agent",
                  "full_name": "Free Agent",
                  "position": "RB",
                  "team": null,
                  "active": true
                }
                """);

        assertThat(player.getTeam()).isNull();
        assertThat(player.getDepthChartPosition()).isNull();
        assertThat(player.getDepthChartOrder()).isNull();
        assertThat(player.getInjuryStatus()).isNull();
    }

    @Test
    @DisplayName("existing espn_id survives a plain sync — two writers, and the blob isn't one of them")
    void espnIdSurvivesPlainSync() throws Exception {
        // espn_id is written by the id-mapping enrichment stage, not the Sleeper blob;
        // the upsert builds a fresh entity, so without an explicit carry-over the merge
        // quietly nulls it (the teamCount family: two writers, one silent overwrite).
        SleeperPlayer sleeperPlayer = blobMapper.readValue(MAHOMES_SHAPE, SleeperPlayer.class);
        when(sleeperClient.fetchAllPlayers())
                .thenReturn(Map.of(sleeperPlayer.playerId(), sleeperPlayer));
        Player existing = Player.builder()
                .id("4046").firstName("Patrick").lastName("Mahomes")
                .fullName("Patrick Mahomes").position("QB").active(true)
                .espnId("3139477")
                .build();
        when(playerRepository.findAll()).thenReturn(List.of(existing));

        new PlayerSyncService(sleeperClient, playerRepository).syncPlayers();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Player>> captor = ArgumentCaptor.forClass(List.class);
        verify(playerRepository).saveAll(captor.capture());
        Player synced = captor.getValue().get(0);
        assertThat(synced.getEspnId()).isEqualTo("3139477");
        assertThat(synced.isNew()).isFalse();
    }

    @Test
    @DisplayName("over-length injury notes are truncated to the column bound, not a failed batch")
    void overLengthInjuryNotesTruncated() throws Exception {
        String longNotes = "x".repeat(300);
        Player player = syncOne("""
                {
                  "player_id": "4046",
                  "full_name": "Patrick Mahomes",
                  "position": "QB",
                  "team": "KC",
                  "active": true,
                  "injury_status": "Out",
                  "injury_notes": "%s"
                }
                """.formatted(longNotes));

        assertThat(player.getInjuryNotes()).hasSize(255);
    }
}
