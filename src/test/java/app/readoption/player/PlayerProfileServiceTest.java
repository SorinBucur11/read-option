package app.readoption.player;

import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerscoring.PlayerScoring;
import app.readoption.playerscoring.PlayerScoringRepository;
import app.readoption.scoring.ScoringFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerProfileService — composite assembly + positional-rank guard")
class PlayerProfileServiceTest {

    private static final int CURRENT_SEASON = 2026;
    private static final ScoringFormat FORMAT = ScoringFormat.STANDARD_6PT;

    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerScoringRepository playerScoringRepository;
    @Mock private PlayerProjectionRepository playerProjectionRepository;

    private PlayerProfileService service;

    @BeforeEach
    void setUp() {
        // Constructor mixes mocks with a plain int, so build it by hand rather than @InjectMocks.
        service = new PlayerProfileService(
                playerRepository, playerScoringRepository, playerProjectionRepository, CURRENT_SEASON);
    }

    private Player player(String id, String position) {
        return Player.builder()
                .id(id)
                .fullName("Test Player")
                .position(position)
                .team("KC")
                .build();
    }

    private PlayerScoring scoring(String id, int year, String total) {
        return PlayerScoring.builder()
                .playerId(id)
                .year(year)
                .scoringFormat(FORMAT)
                .totalPoints(new BigDecimal(total))
                .pointsPerGame(new BigDecimal("10.00"))
                .gamesPlayed(17)
                .build();
    }

    @Test
    @DisplayName("assembles history (past years) + projection (current year) + positional rank")
    void buildsFullProfile() {
        String id = "4046";
        when(playerRepository.findById(id)).thenReturn(Optional.of(player(id, "QB")));
        when(playerScoringRepository.findByPlayerIdAndScoringFormatOrderByYearAsc(id, FORMAT))
                .thenReturn(List.of(
                        scoring(id, 2024, "300.00"),
                        scoring(id, 2025, "320.00"),
                        scoring(id, 2026, "323.62")));   // 2026 == current -> the projection line
        PlayerProjection proj = PlayerProjection.builder()
                .playerId(id).year(2026).adpStd(new BigDecimal("102.0")).build();
        when(playerProjectionRepository.findByPlayerIdAndYear(id, 2026)).thenReturn(Optional.of(proj));
        when(playerProjectionRepository.countBetterAdpAtPosition(eq(2026), eq("QB"), eq("STANDARD"), any()))
                .thenReturn(7L);

        PlayerProfile profile = service.getProfile(id, FORMAT);

        assertThat(profile.fullName()).isEqualTo("Test Player");
        assertThat(profile.history()).extracting(SeasonScore::year).containsExactly(2024, 2025);
        assertThat(profile.projection()).isNotNull();
        assertThat(profile.projection().year()).isEqualTo(2026);
        assertThat(profile.projection().adp()).isEqualByComparingTo("102.0");
        assertThat(profile.projection().positionalRank()).isEqualTo(8);   // 7 better + 1
    }

    @Test
    @DisplayName("unknown player id -> PlayerNotFoundException")
    void unknownPlayerThrows() {
        when(playerRepository.findById("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getProfile("NOPE", FORMAT))
                .isInstanceOf(PlayerNotFoundException.class);
    }

    @Test
    @DisplayName("no current-season score -> projection is null, no rank query")
    void noProjectionWhenNoCurrentSeasonScore() {
        String id = "4046";
        when(playerRepository.findById(id)).thenReturn(Optional.of(player(id, "QB")));
        when(playerScoringRepository.findByPlayerIdAndScoringFormatOrderByYearAsc(id, FORMAT))
                .thenReturn(List.of(scoring(id, 2024, "300.00"), scoring(id, 2025, "320.00")));

        PlayerProfile profile = service.getProfile(id, FORMAT);

        assertThat(profile.history()).hasSize(2);
        assertThat(profile.projection()).isNull();
        verify(playerProjectionRepository, never())
                .countBetterAdpAtPosition(anyInt(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("null ADP -> null positional rank, and the ranking query is never run")
    void nullAdpSkipsRankQuery() {
        String id = "4046";
        when(playerRepository.findById(id)).thenReturn(Optional.of(player(id, "QB")));
        when(playerScoringRepository.findByPlayerIdAndScoringFormatOrderByYearAsc(id, FORMAT))
                .thenReturn(List.of(scoring(id, 2026, "323.62")));
        PlayerProjection proj = PlayerProjection.builder()
                .playerId(id).year(2026).adpStd(null).build();   // undrafted / sentinel-null
        when(playerProjectionRepository.findByPlayerIdAndYear(id, 2026)).thenReturn(Optional.of(proj));

        PlayerProfile profile = service.getProfile(id, FORMAT);

        assertThat(profile.projection()).isNotNull();
        assertThat(profile.projection().adp()).isNull();
        assertThat(profile.projection().positionalRank()).isNull();
        verify(playerProjectionRepository, never())
                .countBetterAdpAtPosition(anyInt(), anyString(), anyString(), any());
    }
}