package app.readoption.playerscoring;

import app.readoption.config.SpringDataWebConfig;
import app.readoption.scoring.ScoringFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
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
                eq(2026), eq(ScoringFormat.STANDARD_6PT), isNull(), isNull(), any(Pageable.class)))
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
    @DisplayName("size above the max is rejected with 400 and never reaches the repository")
    void leaderboard_rejectsOversizedPageSize() throws Exception {
        mockMvc.perform(get("/api/scoring/leaderboard")
                        .param("season", "2026")
                        .param("format", "STANDARD_6PT")
                        .param("size", "500"))
                .andExpect(status().isBadRequest());

        verify(playerScoringRepository, never()).findLeaderboard(anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("invalid format enum -> 400 before the method runs")
    void leaderboardBadEnum() throws Exception {
        mockMvc.perform(get("/api/scoring/leaderboard?format=BOGUS"))
                .andExpect(status().isBadRequest());
    }
}