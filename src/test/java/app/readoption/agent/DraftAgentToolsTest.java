package app.readoption.agent;

import app.readoption.draft.DraftPick;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftService;
import app.readoption.draft.DraftStateView;
import app.readoption.draft.DraftStatus;
import app.readoption.news.PlayerNewsSearchService;
import app.readoption.news.PlayerNewsView;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import app.readoption.team.TeamContextService;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
import java.util.Optional;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private static final int CURRENT_SEASON = 2026;

    @Mock private DraftService draftService;
    @Mock private DraftBoardService draftBoardService;
    @Mock private ProfileScoringService profileScoringService;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerProjectionRepository projectionRepository;
    @Mock private DraftPickRepository draftPickRepository;
    @Mock private TeamContextService teamContextService;
    @Mock private PlayerNewsSearchService newsSearchService;

    private final JsonMapper objectMapper = new JsonMapper();

    private DraftAgentTools tools() {
        return new DraftAgentTools(SESSION_ID, RULES, CURRENT_SEASON, draftService,
                draftBoardService, profileScoringService, playerRepository,
                projectionRepository, draftPickRepository, teamContextService,
                newsSearchService);
    }

    @Test
    @DisplayName("generated schemas expose ONLY the documented params — no sessionId, no rules")
    void schemasExposeOnlyDocumentedParams() throws Exception {
        ToolCallback[] callbacks = ToolCallbacks.from(tools());

        assertThat(callbacks)
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("getDraftState", "getDraftBoard", "getPlayerProfile",
                        "findPlayer", "getTeamContext", "searchPlayerNews");

        for (ToolCallback callback : callbacks) {
            String schema = callback.getToolDefinition().inputSchema();
            List<String> params = propertyNames(schema);
            switch (callback.getToolDefinition().name()) {
                case "getDraftState" -> assertThat(params).isEmpty();
                case "getDraftBoard" -> assertThat(params)
                        .containsExactlyInAnyOrder("position", "limit");
                case "getPlayerProfile" -> assertThat(params).containsExactly("playerId");
                case "findPlayer" -> assertThat(params).containsExactly("name");
                case "getTeamContext" -> assertThat(params)
                        .containsExactlyInAnyOrder("team", "position");
                // topK is a server-side property and the session is Java-bound:
                // neither may ever surface as a schema field.
                case "searchPlayerNews" -> assertThat(params)
                        .containsExactlyInAnyOrder("playerId", "query");
                default -> throw new IllegalStateException("unexpected tool: "
                        + callback.getToolDefinition().name());
            }
            assertThat(schema).doesNotContain("sessionId").doesNotContain("scoringRules")
                    .doesNotContain("topK");
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
                new PlayerProfileView("4866", "Saquon Barkley", "RB", "PHI",
                        "RB", 1, List.of(), "no injury reported", null, null,
                        "9", List.of("W1 vs DAL (home)"), List.of(), null));

        tools().getPlayerProfile("4866");

        verify(profileScoringService).profile("4866", RULES);
    }

    // ----- findPlayer (4.3.1 Commit E) -----

    @Test
    @DisplayName("a blank name throws — surfaced to the model as an error tool response")
    void blankNameThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().findPlayer("  "))
                .withMessageContaining("name");
    }

    @Test
    @DisplayName("Aiyuk-shaped regression: active + projection + undrafted + off the board slice "
            + "-> drafted=false, hasProjection=true, takenAtPick omitted")
    void aiyukShapedCandidateIsAvailable() {
        Player aiyuk = player("A99", "Brandon Aiyuk", "WR", "SF", true);
        when(playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Aiyuk"))
                .thenReturn(List.of(aiyuk));
        when(draftPickRepository.findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("A99")))
                .thenReturn(List.of());
        when(projectionRepository.findPlayerIdsWithProjection(CURRENT_SEASON, List.of("A99")))
                .thenReturn(List.of("A99"));

        List<PlayerSearchResult> results = tools().findPlayer("Aiyuk");

        assertThat(results).hasSize(1);
        PlayerSearchResult result = results.get(0);
        assertThat(result.playerId()).isEqualTo("A99");
        assertThat(result.drafted()).isFalse();
        assertThat(result.takenAtPick()).isNull();
        assertThat(result.hasProjection()).isTrue();
        assertThat(result.team()).isEqualTo("SF");
    }

    @Test
    @DisplayName("a drafted candidate carries the taking pick, computed against THIS session")
    void draftedCandidateCarriesTakingPick() {
        Player drafted = player("D1", "Drafted Guy", "RB", "KC", true);
        when(playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Drafted"))
                .thenReturn(List.of(drafted));
        when(draftPickRepository.findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("D1")))
                .thenReturn(List.of(DraftPick.builder()
                        .sessionId(SESSION_ID).overallPickNo(12).playerId("D1").build()));
        when(projectionRepository.findPlayerIdsWithProjection(CURRENT_SEASON, List.of("D1")))
                .thenReturn(List.of("D1"));

        List<PlayerSearchResult> results = tools().findPlayer("Drafted");

        assertThat(results.get(0).drafted()).isTrue();
        assertThat(results.get(0).takenAtPick()).isEqualTo(12);
        verify(draftPickRepository).findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("D1"));
    }

    @Test
    @DisplayName("a free-agent candidate degrades team to the NO_TEAM label, never null")
    void freeAgentCandidateGetsNoTeamLabel() {
        Player freeAgent = player("F1", "Brandin Cooks", "WR", null, true);
        when(playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Cooks"))
                .thenReturn(List.of(freeAgent));
        when(draftPickRepository.findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("F1")))
                .thenReturn(List.of());
        when(projectionRepository.findPlayerIdsWithProjection(CURRENT_SEASON, List.of("F1")))
                .thenReturn(List.of());

        List<PlayerSearchResult> results = tools().findPlayer("Cooks");

        assertThat(results.get(0).team()).isEqualTo(TeamContextService.NO_TEAM);
        assertThat(results.get(0).hasProjection()).isFalse();
    }

    @Test
    @DisplayName("no match: empty list, and the batch lookups never fire")
    void noMatchReturnsEmpty() {
        when(playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Nobody"))
                .thenReturn(List.of());

        assertThat(tools().findPlayer("Nobody")).isEmpty();
        verifyNoInteractions(draftPickRepository, projectionRepository);
    }

    // ----- getTeamContext (4.3.1 Commit F) -----

    @Test
    @DisplayName("a blank team throws — surfaced to the model as an error tool response")
    void blankTeamThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().getTeamContext(" ", null))
                .withMessageContaining("team");
    }

    @Test
    @DisplayName("an unknown team degrades loudly in the RESULT — the note names the bad input")
    void unknownTeamDegradesInResult() {
        when(teamContextService.teamRoom("XYZ", null, CURRENT_SEASON))
                .thenReturn(Optional.empty());

        TeamRoomView view = tools().getTeamContext("xyz", null);

        assertThat(view.note()).contains("XYZ").contains("no context available");
        assertThat(view.byeWeek()).isNull();
        assertThat(view.room()).isNull();
    }

    @Test
    @DisplayName("the room rides the bound session: drafted flags batch-computed, F1 injury label")
    void roomCarriesDraftedFlagsAndInjuryLabels() {
        Player starter = player("CMC", "Christian McCaffrey", "RB", "SF", true);
        starter.setDepthChartPosition("RB");
        starter.setDepthChartOrder(1);
        starter.setInjuryStatus("Questionable");
        Player handcuff = player("H1", "Backfield Handcuff", "RB", "SF", true);
        handcuff.setDepthChartPosition("RB");
        handcuff.setDepthChartOrder(2);
        // lingering note, no status: the F1 gate must read this as no injury
        handcuff.setInjuryNotes("Surgery");
        when(teamContextService.teamRoom("SF", "RB", CURRENT_SEASON))
                .thenReturn(Optional.of(new TeamContextService.TeamRoom(
                        "9", List.of("W1 vs NYJ (home)"), List.of(starter, handcuff))));
        when(draftPickRepository.findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("CMC", "H1")))
                .thenReturn(List.of(DraftPick.builder()
                        .sessionId(SESSION_ID).overallPickNo(3).playerId("CMC").build()));

        TeamRoomView view = tools().getTeamContext("sf", "rb");   // case-normalized

        assertThat(view.team()).isEqualTo("SF");
        assertThat(view.positionFilter()).isEqualTo("RB");
        assertThat(view.note()).isNull();
        assertThat(view.byeWeek()).isEqualTo("9");
        assertThat(view.room()).extracting(TeamRoomView.RoomEntry::playerId)
                .containsExactly("CMC", "H1");
        assertThat(view.room().get(0).drafted()).isTrue();
        assertThat(view.room().get(1).drafted()).isFalse();
        assertThat(view.room().get(1).injuryStatus()).isEqualTo("no injury reported");
        verify(draftPickRepository).findBySessionIdAndPlayerIdIn(SESSION_ID, List.of("CMC", "H1"));
    }

    @Test
    @DisplayName("a RoomEntry with null depthChartOrder serializes without the key (NON_NULL)")
    void roomEntryOmitsNullDepthChartOrder() throws Exception {
        // Class-level NON_NULL on TeamRoomView does not cascade to the nested
        // record — this pins the annotation on RoomEntry itself.
        TeamRoomView.RoomEntry entry = new TeamRoomView.RoomEntry(
                "H1", "Backfield Handcuff", "RB", null, "no injury reported", false);

        String json = objectMapper.writeValueAsString(entry);

        assertThat(json).doesNotContain("depthChartOrder");
        assertThat(json).contains("\"playerId\":\"H1\"");
    }

    @Test
    @DisplayName("an unknown position filter throws before any lookup")
    void teamContextUnknownPositionThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().getTeamContext("SF", "PUNTER"))
                .withMessageContaining("PUNTER");
        verifyNoInteractions(teamContextService);
    }

    // ----- searchPlayerNews (4.4 Commit D) -----

    @Test
    @DisplayName("searchPlayerNews delegates trimmed args to the news layer")
    void newsDelegatesTrimmedArgs() {
        when(newsSearchService.searchForPlayer("4046", "injury recovery status"))
                .thenReturn(PlayerNewsView.degraded("4046", "NO_NEWS_FOUND"));

        PlayerNewsView view = tools().searchPlayerNews(" 4046 ", " injury recovery status ");

        assertThat(view.note()).isEqualTo("NO_NEWS_FOUND");
        verify(newsSearchService).searchForPlayer("4046", "injury recovery status");
    }

    @Test
    @DisplayName("blank news args throw - surfaced to the model as error tool responses")
    void blankNewsArgsThrow() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().searchPlayerNews(" ", "query"))
                .withMessageContaining("playerId");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools().searchPlayerNews("4046", " "))
                .withMessageContaining("query");
        verifyNoInteractions(newsSearchService);
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
        return new ArrayList<>(properties.propertyNames());
    }
}
