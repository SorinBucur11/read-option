package app.readoption.agent;

import app.readoption.agent.dto.AdviceRequest;
import app.readoption.agent.dto.AdviceResponse;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.InvalidDraftRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DraftAgentController.class)
@DisplayName("DraftAgentController — web layer: advice turn, RFC 9457 failures")
class DraftAgentControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private DraftAgentService draftAgentService;

    @Test
    @DisplayName("POST /advise happy path -> 200 with advice + loop instrumentation")
    void advise() throws Exception {
        when(draftAgentService.advise(eq(1L), anyString()))
                .thenReturn(new AdviceResponse("Take Barkley.", 2, 3500L, 4200L));

        mockMvc.perform(post("/api/draft/sessions/1/advise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AdviceRequest("Who should I pick?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.advice").value("Take Barkley."))
                .andExpect(jsonPath("$.iterations").value(2))
                .andExpect(jsonPath("$.totalTokens").value(3500))
                .andExpect(jsonPath("$.latencyMs").value(4200));
    }

    @Test
    @DisplayName("POST /advise on an unknown session -> 404 ProblemDetail")
    void adviseUnknownSession() throws Exception {
        when(draftAgentService.advise(eq(99L), anyString()))
                .thenThrow(new DraftSessionNotFoundException(99L));

        mockMvc.perform(post("/api/draft/sessions/99/advise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AdviceRequest("Who should I pick?"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Draft Session Not Found"));
    }

    @Test
    @DisplayName("POST /advise with a blank message -> 400 ProblemDetail")
    void adviseBlankMessage() throws Exception {
        when(draftAgentService.advise(1L, " "))
                .thenThrow(new InvalidDraftRequestException("message must not be blank"));

        mockMvc.perform(post("/api/draft/sessions/1/advise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AdviceRequest(" "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid Draft Request"));
    }

    @Test
    @DisplayName("POST /advise loop-limit breach -> 500 ProblemDetail without internals")
    void adviseLoopLimit() throws Exception {
        when(draftAgentService.advise(eq(1L), anyString()))
                .thenThrow(new AgentLoopLimitException(1L, 8));

        mockMvc.perform(post("/api/draft/sessions/1/advise")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AdviceRequest("Who should I pick?"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Agent Loop Limit Exceeded"));
    }
}
