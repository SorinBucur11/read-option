package app.readoption.playerscoring;

import app.readoption.config.SpringDataWebConfig;
import app.readoption.scoring.ScoringFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlayerScoringController.class)
@Import(SpringDataWebConfig.class)   // pulls VIA_DTO into the slice so the paged envelope renders
@DisplayName("PlayerScoringController /leaderboard — web layer")
class PlayerScoringControllerTest {

    @Autowired private MockMvc mockMvc;

    // Mock EVERY collaborator the controller injects, or the slice context won't start.
    @MockitoBean private PlayerScoringRepository playerScoringRepository;
    @MockitoBean private PlayerScoringService playerScoringService;

    private Page<LeaderboardRow> onePage() {
        LeaderboardRow row = new LeaderboardRow(
                "4046", "Patrick Mahomes", "QB", "KC",
                new BigDecimal("323.62"), new BigDecimal("19.04"), 17);
        return new PageImpl<>(List.of(row), PageRequest.of(0, 25), 1);
    }

    @Test
    @DisplayName("defaults applied (season from config, STANDARD_6PT); returns VIA_DTO envelope")
    void leaderboardDefaults() throws Exception {
        when(playerScoringRepository.findLeaderboard(
                eq(2026), eq(ScoringFormat.STANDARD_6PT), isNull(), any(Pageable.class)))
                .thenReturn(onePage());

        mockMvc.perform(get("/api/scoring/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("Patrick Mahomes"))
                .andExpect(jsonPath("$.content[0].totalPoints").value(323.62))
                .andExpect(jsonPath("$.page.size").value(25))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("size above MAX_PAGE_SIZE is clamped before the repository is hit")
    void leaderboardClampsSize() throws Exception {
        when(playerScoringRepository.findLeaderboard(any(), any(), any(), any(Pageable.class)))
                .thenReturn(onePage());

        mockMvc.perform(get("/api/scoring/leaderboard?size=500"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(playerScoringRepository).findLeaderboard(any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);   // clamped to MAX_PAGE_SIZE
    }

    @Test
    @DisplayName("invalid format enum -> 400 before the method runs")
    void leaderboardBadEnum() throws Exception {
        mockMvc.perform(get("/api/scoring/leaderboard?format=BOGUS"))
                .andExpect(status().isBadRequest());
    }
}