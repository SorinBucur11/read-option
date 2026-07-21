package app.readoption.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DraftSyncController.class)
@DisplayName("DraftSyncController — web layer: link, status, stop, RFC 9457 failures")
class DraftSyncControllerTest {

    private static final String DRAFT_ID = "1382999990000000001";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DraftSyncRunner runner;

    private static DraftSyncStatus.Report report(DraftSyncStatus state, Long sessionId,
                                                 int picks, String error) {
        return new DraftSyncStatus.Report(DRAFT_ID, state, sessionId, picks,
                Instant.parse("2026-07-21T18:00:00Z"), error);
    }

    @Test
    @DisplayName("POST /api/sleeper/sync -> 202 with the initial report")
    void startSync() throws Exception {
        when(runner.start(DRAFT_ID, 42L))
                .thenReturn(report(DraftSyncStatus.WATCHING, null, 0, null));

        mockMvc.perform(post("/api/sleeper/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"" + DRAFT_ID + "\",\"leagueConfigId\":42}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.draftId").value(DRAFT_ID))
                .andExpect(jsonPath("$.state").value("WATCHING"))
                .andExpect(jsonPath("$.sessionId").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("POST start while already running -> 409 ProblemDetail")
    void startConflict() throws Exception {
        when(runner.start(DRAFT_ID, 42L))
                .thenThrow(new DraftSyncConflictException("a sync is already running for draft " + DRAFT_ID));

        mockMvc.perform(post("/api/sleeper/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"" + DRAFT_ID + "\",\"leagueConfigId\":42}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Draft Sync Conflict"));
    }

    @Test
    @DisplayName("POST start with blank draftId -> 400 validation failure")
    void startValidation() throws Exception {
        mockMvc.perform(post("/api/sleeper/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftId\":\"\",\"leagueConfigId\":42}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET status -> 200 report with session and picks")
    void getStatus() throws Exception {
        when(runner.status(DRAFT_ID))
                .thenReturn(report(DraftSyncStatus.SYNCING, 99L, 37, null));

        mockMvc.perform(get("/api/sleeper/sync/{draftId}", DRAFT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SYNCING"))
                .andExpect(jsonPath("$.sessionId").value(99))
                .andExpect(jsonPath("$.picksSynced").value(37));
    }

    @Test
    @DisplayName("GET status for unknown draftId -> 404 ProblemDetail")
    void getStatusUnknown() throws Exception {
        when(runner.status("nope")).thenThrow(new DraftSyncNotFoundException("nope"));

        mockMvc.perform(get("/api/sleeper/sync/{draftId}", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Draft Sync Not Found"));
    }

    @Test
    @DisplayName("POST stop -> 200 with the final report")
    void stopSync() throws Exception {
        when(runner.stop(DRAFT_ID))
                .thenReturn(report(DraftSyncStatus.STOPPED, 99L, 37, null));

        mockMvc.perform(post("/api/sleeper/sync/{draftId}/stop", DRAFT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("STOPPED"));
    }
}
