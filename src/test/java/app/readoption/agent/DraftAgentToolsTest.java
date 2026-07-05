package app.readoption.agent;

import app.readoption.draft.DraftService;
import app.readoption.draft.DraftStateView;
import app.readoption.draft.DraftStatus;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session-owned scope is a safety property: the generated tool schemas must give
 * the model no field to address another session or another scoring format, and
 * every delegation must carry the Java-bound {@code sessionId}/{@code rules}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftAgentTools — schema scope and bound-field delegation")
class DraftAgentToolsTest {

    private static final long SESSION_ID = 7L;
    private static final ScoringRules RULES = ScoringRules.of(
            new BigDecimal("0.5"), new BigDecimal("4"),
            ScoringRules.DEFAULT_INTERCEPTION_POINTS, new BigDecimal("0.5"));

    @Mock private DraftService draftService;
    @Mock private DraftBoardService draftBoardService;
    @Mock private ProfileScoringService profileScoringService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DraftAgentTools tools() {
        return new DraftAgentTools(SESSION_ID, RULES, draftService, draftBoardService,
                profileScoringService);
    }

    @Test
    @DisplayName("generated schemas expose ONLY the documented params — no sessionId, no rules")
    void schemasExposeOnlyDocumentedParams() throws Exception {
        ToolCallback[] callbacks = ToolCallbacks.from(tools());

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("getDraftState", "getDraftBoard", "getPlayerProfile");

        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            List<String> params = propertyNames(schema);
            switch (callback.getToolDefinition().name()) {
                case "getDraftState" -> assertThat(params).isEmpty();
                case "getDraftBoard" -> assertThat(params)
                        .containsExactlyInAnyOrder("position", "limit");
                case "getPlayerProfile" -> assertThat(params).containsExactly("playerId");
                default -> throw new IllegalStateException("unexpected tool: "
                        + callback.getToolDefinition().name());
            }
            assertThat(schema).doesNotContain("sessionId").doesNotContain("scoringRules");
        }
    }

    @Test
    @DisplayName("getDraftState binds the session server-side")
    void stateDelegatesWithBoundSession() {
        when(draftService.getState(anyLong())).thenReturn(emptyState());

        tools().getDraftState();

        verify(draftService).getState(SESSION_ID);
    }

    @Test
    @DisplayName("getDraftBoard parses the position filter and applies the default limit")
    void boardDelegatesWithDefaults() {
        when(draftBoardService.getBoard(anyLong(), any(), anyInt())).thenReturn(emptyBoard());

        tools().getDraftBoard("rb", null);

        verify(draftBoardService).getBoard(SESSION_ID, Position.RB, 20);
    }

    @Test
    @DisplayName("getDraftBoard clamps an oversized limit to 50 instead of failing the turn")
    void boardClampsLimit() {
        when(draftBoardService.getBoard(anyLong(), any(), anyInt())).thenReturn(emptyBoard());

        tools().getDraftBoard(null, 500);

        verify(draftBoardService).getBoard(SESSION_ID, null, 50);
    }

    @Test
    @DisplayName("an unknown position throws — surfaced to the model as an error tool response")
    void unknownPositionThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().getDraftBoard("PUNTER", null))
                .withMessageContaining("PUNTER");
    }

    @Test
    @DisplayName("getPlayerProfile scores under the bound resolved rules, never a preset default")
    void profileDelegatesWithBoundRules() {
        when(profileScoringService.profile(anyString(), any())).thenReturn(
                new PlayerProfileView("4866", "Saquon Barkley", "RB", "PHI", List.of(), null));

        tools().getPlayerProfile("4866");

        verify(profileScoringService).profile("4866", RULES);
    }

    private static DraftStateView emptyState() {
        return new DraftStateView(SESSION_ID, DraftStatus.ACTIVE, 1, 1, false, 3,
                List.of(), java.util.Map.of(), List.of());
    }

    private static DraftBoardView emptyBoard() {
        return new DraftBoardView(2026, java.util.Map.of(), List.of());
    }

    private List<String> propertyNames(String inputSchema) throws Exception {
        JsonNode properties = objectMapper.readTree(inputSchema).path("properties");
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
