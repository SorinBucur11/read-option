package app.readoption.draft;

import app.readoption.scoring.Position;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DraftController.class)
@DisplayName("DraftController — web layer: sessions, picks, state, RFC 9457 failures")
class DraftControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private DraftService draftService;
    @MockitoBean private DraftBoardService draftBoardService;

    @Test
    @DisplayName("POST /sessions happy path -> 201 with the persisted session")
    void startSession() throws Exception {
        DraftSession session = DraftSession.builder()
                .id(1L).leagueConfigId(42L).season(2026)
                .teamCount(10).userSlot(8).totalRounds(13).status(DraftStatus.ACTIVE)
                .build();
        when(draftService.startSession(any(StartDraftRequest.class))).thenReturn(session);

        mockMvc.perform(post("/api/draft/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartDraftRequest(42L, 8))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.totalRounds").value(13))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /sessions with userSlot=0 -> 400 bean validation, service never reached")
    void startSessionBeanValidation() throws Exception {
        mockMvc.perform(post("/api/draft/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new StartDraftRequest(42L, 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /picks happy path -> 201 pick DTO with the derived team slot")
    void recordPick() throws Exception {
        when(draftService.recordPick(eq(1L), any(RecordPickRequest.class))).thenReturn(
                new DraftPickView(1L, 11, 2, 10, "4866", LocalDateTime.of(2026, 7, 3, 12, 0)));

        mockMvc.perform(post("/api/draft/sessions/1/picks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordPickRequest("4866"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overallPickNo").value(11))
                .andExpect(jsonPath("$.round").value(2))
                .andExpect(jsonPath("$.teamSlot").value(10))
                .andExpect(jsonPath("$.playerId").value("4866"));
    }

    @Test
    @DisplayName("POST /picks duplicate player -> 409 ProblemDetail carrying the taking pick")
    void recordPickDuplicate() throws Exception {
        when(draftService.recordPick(eq(1L), any(RecordPickRequest.class)))
                .thenThrow(new PlayerAlreadyDraftedException("4866", 3));

        mockMvc.perform(post("/api/draft/sessions/1/picks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordPickRequest("4866"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Player Already Drafted"))
                .andExpect(jsonPath("$.playerId").value("4866"))
                .andExpect(jsonPath("$.overallPickNo").value(3));
    }

    @Test
    @DisplayName("POST /picks after COMPLETE -> 409 ProblemDetail with the session status")
    void recordPickAfterComplete() throws Exception {
        when(draftService.recordPick(eq(1L), any(RecordPickRequest.class)))
                .thenThrow(new DraftSessionNotActiveException(1L, DraftStatus.COMPLETE));

        mockMvc.perform(post("/api/draft/sessions/1/picks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RecordPickRequest("4866"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Draft Session Not Active"))
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    @Test
    @DisplayName("GET /state on an unknown session -> 404 ProblemDetail")
    void stateUnknownSession() throws Exception {
        when(draftService.getState(99L)).thenThrow(new DraftSessionNotFoundException(99L));

        mockMvc.perform(get("/api/draft/sessions/99/state"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Draft Session Not Found"));
    }

    @Test
    @DisplayName("GET /state happy path serializes the full view")
    void state() throws Exception {
        DraftStateView view = new DraftStateView(1L, DraftStatus.ACTIVE, 9, 9, false, 4,
                List.of(new DraftStateView.RosterEntry("P8", "Runner Eight", "RB", 1, "9")),
                Map.of("RB", 1),
                List.of(new DraftStateView.GapTeam(9, 2, Map.of())));
        when(draftService.getState(1L)).thenReturn(view);

        mockMvc.perform(get("/api/draft/sessions/1/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentOverallPick").value(9))
                .andExpect(jsonPath("$.onTheClock").value(false))
                .andExpect(jsonPath("$.picksUntilUserNextTurn").value(4))
                .andExpect(jsonPath("$.userRoster[0].name").value("Runner Eight"))
                .andExpect(jsonPath("$.userRoster[0].byeWeek").value("9"))
                .andExpect(jsonPath("$.unfilledSlots.RB").value(1))
                .andExpect(jsonPath("$.gapTeams[0].teamSlot").value(9))
                .andExpect(jsonPath("$.gapTeams[0].picksInGap").value(2));
    }

    @Test
    @DisplayName("GET /board passes position + limit through and serializes the VORP rows")
    void board() throws Exception {
        DraftBoardView view = new DraftBoardView(2026,
                Map.of(Position.RB, new BigDecimal("120.00")),
                List.of(new DraftBoardView.Row("4866", "Saquon Barkley", Position.RB,
                        new BigDecimal("226.00"), new BigDecimal("106.00"), new BigDecimal("2.50"))));
        when(draftBoardService.getBoard(1L, Position.RB, 5)).thenReturn(view);

        mockMvc.perform(get("/api/draft/sessions/1/board")
                        .param("position", "RB").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.replacementLevels.RB").value(120.00))
                .andExpect(jsonPath("$.rows[0].playerId").value("4866"))
                .andExpect(jsonPath("$.rows[0].vorp").value(106.00))
                .andExpect(jsonPath("$.rows[0].adp").value(2.50));
    }

    @Test
    @DisplayName("GET /board with limit above the 50 cap -> 400 ProblemDetail")
    void boardLimitCapped() throws Exception {
        mockMvc.perform(get("/api/draft/sessions/1/board").param("limit", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"));
    }

    @Test
    @DisplayName("GET /board with an unknown position value -> 400 ProblemDetail")
    void boardBadPosition() throws Exception {
        mockMvc.perform(get("/api/draft/sessions/1/board").param("position", "PUNTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Parameter"));
    }
}
