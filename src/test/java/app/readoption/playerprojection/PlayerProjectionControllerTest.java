package app.readoption.playerprojection;

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

@WebMvcTest(PlayerProjectionController.class)
@DisplayName("PlayerProjectionController — web layer")
class PlayerProjectionControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PlayerProjectionSyncService syncService;
    @MockitoBean private PlayerProjectionRepository playerProjectionRepository;

    @Test
    @DisplayName("POST /api/projections/sync/{season} returns 200")
    void syncReturnsOk() throws Exception {
        when(syncService.syncProjections(2026)).thenReturn(150);

        mockMvc.perform(post("/api/projections/sync/2026"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/projections/sync with non-numeric season returns 400")
    void syncBadSeasonReturns400() throws Exception {
        mockMvc.perform(post("/api/projections/sync/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/projections/player/{playerId} returns 200")
    void getByPlayerReturnsOk() throws Exception {
        when(playerProjectionRepository.findByPlayerId("4046")).thenReturn(List.of());

        mockMvc.perform(get("/api/projections/player/4046"))
                .andExpect(status().isOk());
    }
}
