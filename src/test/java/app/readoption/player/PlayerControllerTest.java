package app.readoption.player;

import app.readoption.scoring.ScoringFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PlayerController.class)
@DisplayName("PlayerController /{id}/profile — web layer")
class PlayerControllerTest {

    @Autowired private MockMvc mockMvc;

    // Mock every collaborator PlayerController injects. If your controller's other
    // endpoints (sync, list) inject PlayerRepository / PlayerSyncService, add @MockitoBean
    // fields for them here too, or the context won't start.
    @MockitoBean private PlayerProfileService playerProfileService;
    @MockitoBean private PlayerRepository playerRepository;
    @MockitoBean private PlayerSyncService playerSyncService;
    @MockitoBean private PlayerDataSyncService playerDataSyncService;
    @MockitoBean private PlayerIdMappingService playerIdMappingService;

    @Test
    @DisplayName("known player -> 200 with composite body")
    void profileOk() throws Exception {
        PlayerProfile profile = new PlayerProfile(
                "4046", "Patrick Mahomes", "QB", "KC", ScoringFormat.STANDARD_6PT,
                List.of(new SeasonScore(2025, new BigDecimal("320.00"), new BigDecimal("20.00"), 16)),
                new ProjectionScore(2026, new BigDecimal("323.62"), new BigDecimal("19.04"),
                        17, new BigDecimal("102.0"), 8));
        when(playerProfileService.getProfile("4046", ScoringFormat.STANDARD_6PT)).thenReturn(profile);

        mockMvc.perform(get("/api/players/4046/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Patrick Mahomes"))
                .andExpect(jsonPath("$.history[0].year").value(2025))
                .andExpect(jsonPath("$.projection.positionalRank").value(8));
    }

    @Test
    @DisplayName("unknown player -> 404 via @ResponseStatus")
    void profileNotFound() throws Exception {
        when(playerProfileService.getProfile("NOPE", ScoringFormat.STANDARD_6PT))
                .thenThrow(new PlayerNotFoundException("NOPE"));

        mockMvc.perform(get("/api/players/NOPE/profile"))
                .andExpect(status().isNotFound());
    }
}