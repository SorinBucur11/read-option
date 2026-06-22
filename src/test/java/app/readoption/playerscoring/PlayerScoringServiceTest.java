package app.readoption.playerscoring;

import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerScoringService — season routing and recompute")
class PlayerScoringServiceTest {

    private static final int CURRENT_SEASON = 2026;
    private static final ScoringResult DUMMY_RESULT =
            new ScoringResult(new BigDecimal("100.00"), new BigDecimal("5.88"));

    @Mock private PlayerStatsRepository playerStatsRepository;
    @Mock private PlayerProjectionRepository playerProjectionRepository;
    @Mock private PlayerScoringRepository playerScoringRepository;
    @Mock private ScoringService scoringService;

    private PlayerScoringService service;

    @BeforeEach
    void setUp() {
        service = new PlayerScoringService(
                playerStatsRepository, playerProjectionRepository,
                playerScoringRepository, scoringService, CURRENT_SEASON);
    }

    private PlayerStats stat(String id, int year) {
        return PlayerStats.builder().playerId(id).year(year).games(17).gamesPlayed(17).build();
    }

    private PlayerProjection proj(String id, int year) {
        return PlayerProjection.builder().playerId(id).year(year).gamesPlayed(17).build();
    }

    @Test
    @DisplayName("past season (< current) reads PlayerStatsRepository, never projections")
    void pastSeasonUsesStatsRepo() {
        when(playerStatsRepository.findByYear(2025)).thenReturn(List.of(stat("1", 2025)));
        when(playerScoringRepository.findByYear(2025)).thenReturn(List.of());
        when(scoringService.calculate(any(), any())).thenReturn(DUMMY_RESULT);

        service.computeAndSaveForSeason(2025);

        verify(playerStatsRepository).findByYear(2025);
        verify(playerProjectionRepository, never()).findByYear(anyInt());
    }

    @Test
    @DisplayName("current season (== current) reads PlayerProjectionRepository, never stats")
    void currentSeasonUsesProjectionRepo() {
        when(playerProjectionRepository.findByYear(2026)).thenReturn(List.of(proj("1", 2026)));
        when(playerScoringRepository.findByYear(2026)).thenReturn(List.of());
        when(scoringService.calculate(any(), any())).thenReturn(DUMMY_RESULT);

        service.computeAndSaveForSeason(2026);

        verify(playerProjectionRepository).findByYear(2026);
        verify(playerStatsRepository, never()).findByYear(anyInt());
    }

    @Test
    @DisplayName("future season (> current) also reads projections, never stats")
    void futureSeasonUsesProjectionRepo() {
        when(playerProjectionRepository.findByYear(2027)).thenReturn(List.of(proj("1", 2027)));
        when(playerScoringRepository.findByYear(2027)).thenReturn(List.of());
        when(scoringService.calculate(any(), any())).thenReturn(DUMMY_RESULT);

        service.computeAndSaveForSeason(2027);

        verify(playerProjectionRepository).findByYear(2027);
        verify(playerStatsRepository, never()).findByYear(anyInt());
    }

    @Test
    @DisplayName("empty source for past season returns 0 and never calls saveAll")
    void emptySourceReturnsZero() {
        when(playerStatsRepository.findByYear(2024)).thenReturn(List.of());

        int result = service.computeAndSaveForSeason(2024);

        assertThat(result).isZero();
        verify(playerScoringRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("recomputeAllSeasons unions distinct years from both repos and routes each correctly")
    void recomputeUnionsYearsAndRoutes() {
        when(playerStatsRepository.findDistinctYears()).thenReturn(List.of(2024, 2025));
        when(playerProjectionRepository.findDistinctYears()).thenReturn(List.of(2025, 2026));
        // 2024 and 2025 are past seasons (< 2026) → stats; 2026 is current → projections
        when(playerStatsRepository.findByYear(2024)).thenReturn(List.of());
        when(playerStatsRepository.findByYear(2025)).thenReturn(List.of());
        when(playerProjectionRepository.findByYear(2026)).thenReturn(List.of());

        service.recomputeAllSeasons();

        verify(playerStatsRepository).findDistinctYears();
        verify(playerProjectionRepository).findDistinctYears();
        verify(playerStatsRepository).findByYear(2024);
        verify(playerStatsRepository).findByYear(2025);
        verify(playerProjectionRepository).findByYear(2026);
        // 2025 is in both lists but is a past season — projections must never be called for it
        verify(playerProjectionRepository, never()).findByYear(2025);
    }
}
