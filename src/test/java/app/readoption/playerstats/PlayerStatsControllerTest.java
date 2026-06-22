package app.readoption.playerstats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlayerStatsController.class)
@DisplayName("PlayerStatsController — web layer")
class PlayerStatsControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PlayerStatsSyncService syncService;
    @MockitoBean private PlayerStatsRepository playerStatsRepository;

    @Test
    @DisplayName("POST /api/stats/sync/{season} returns 200")
    void syncReturnsOk() throws Exception {
        when(syncService.syncStats(2025)).thenReturn(10);

        mockMvc.perform(post("/api/stats/sync/2025"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/stats/sync with non-numeric season returns 400")
    void syncBadSeasonReturns400() throws Exception {
        mockMvc.perform(post("/api/stats/sync/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/stats/player/{playerId} returns 200")
    void getByPlayerReturnsOk() throws Exception {
        when(playerStatsRepository.findByPlayerId("4046")).thenReturn(List.of());

        mockMvc.perform(get("/api/stats/player/4046"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/stats/season/{year} returns 200")
    void getBySeasonReturnsOk() throws Exception {
        when(playerStatsRepository.findByYear(2025)).thenReturn(List.of());

        mockMvc.perform(get("/api/stats/season/2025"))
                .andExpect(status().isOk());
    }
}
