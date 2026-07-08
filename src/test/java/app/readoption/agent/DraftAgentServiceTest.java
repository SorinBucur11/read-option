package app.readoption.agent;

import app.readoption.agent.dto.AdviceResponse;
import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftService;
import app.readoption.draft.DraftSession;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.DraftSessionRepository;
import app.readoption.draft.DraftStatus;
import app.readoption.draft.InvalidDraftRequestException;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import app.readoption.team.TeamContextService;
import app.readoption.valuation.DraftBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.core.io.ByteArrayResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The manual loop is the load-bearing logic: scripted {@link ChatModel} responses,
 * mocked {@link ToolCallingManager} (its {@code conversationHistory()} contract is
 * asserted to feed the next prompt), real {@link MessageWindowChatMemory} for the
 * memory contract. No live model, ever.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftAgentService — the manual loop: termination, cap, memory, instrumentation")
class DraftAgentServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long CONFIG_ID = 42L;

    @Mock private ChatModel chatModel;
    @Mock private ToolCallingManager toolCallingManager;
    @Mock private DraftSessionRepository sessionRepository;
    @Mock private LeagueConfigRepository leagueConfigRepository;
    @Mock private DraftService draftService;
    @Mock private DraftBoardService draftBoardService;
    @Mock private ProfileScoringService profileScoringService;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerProjectionRepository projectionRepository;
    @Mock private DraftPickRepository draftPickRepository;
    @Mock private TeamContextService teamContextService;

    private ChatMemory memory;

    @BeforeEach
    void freshMemory() {
        memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }

    private DraftAgentService service(int maxIterations) {
        AgentProperties properties = new AgentProperties("claude-sonnet-4-6", maxIterations, 20);
        AgentPromptBuilder promptBuilder = new AgentPromptBuilder(new ByteArrayResource(
                "You are a draft advisor.".getBytes(StandardCharsets.UTF_8)));
        return new DraftAgentService(chatModel, toolCallingManager, memory, promptBuilder,
                properties, sessionRepository, leagueConfigRepository,
                draftService, draftBoardService, profileScoringService,
                playerRepository, projectionRepository, draftPickRepository,
                teamContextService, 2026);
    }

    private void stubSession() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(
                DraftSession.builder()
                        .id(SESSION_ID).leagueConfigId(CONFIG_ID).season(2026)
                        .teamCount(12).userSlot(8).totalRounds(15).status(DraftStatus.ACTIVE)
                        .build()));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(
                LeagueConfig.builder()
                        .id(CONFIG_ID)
                        .receptionFormat(ReceptionFormat.HALF_PPR)
                        .passingTdPoints(new BigDecimal("4"))
                        .interceptionPoints(new BigDecimal("-2"))
                        .teReceptionBonus(new BigDecimal("0.5"))
                        .teamCount(12)
                        .qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(1)
                        .flexEligible(Set.of(Position.RB, Position.WR))
                        .superflexSlots(0).benchSlots(8)
                        .build()));
    }

    private static ChatResponse toolCallResponse() {
        AssistantMessage withToolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "getDraftState", "{}")))
                .build();
        return ChatResponse.builder()
                .generations(List.of(new Generation(withToolCall)))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(100, 20)).build())
                .build();
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage(text))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(200, 30)).build())
                .build();
    }

    private static ToolExecutionResult executionResult(List<Message> conversationHistory) {
        return ToolExecutionResult.builder().conversationHistory(conversationHistory).build();
    }

    @Test
    @DisplayName("one tool round trip: executeToolCalls once, next prompt is the conversation history, then terminates")
    void loopExecutesToolsThenTerminates() {
        stubSession();
        List<Message> historyAfterTools = List.of(
                new SystemMessage("s"), new UserMessage("u"),
                new AssistantMessage("tool call turn"),
                ToolResponseMessage.builder().responses(List.of()).build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse(), textResponse("Take Barkley."));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult(historyAfterTools));

        AdviceResponse advice = service(8).advise(SESSION_ID, "Who should I pick?");

        assertThat(advice.advice()).isEqualTo("Take Barkley.");
        assertThat(advice.iterations()).isEqualTo(1);
        assertThat(advice.totalTokens()).isEqualTo(350);   // 120 + 230 across two calls
        verify(toolCallingManager, times(1)).executeToolCalls(any(), any());

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        // Second prompt is built PURELY from conversationHistory() — system message
        // carried forward by the manager, never re-added.
        assertThat(prompts.getAllValues().get(1).getInstructions()).isEqualTo(historyAfterTools);
    }

    @Test
    @DisplayName("cap exceeded: loud AgentLoopLimitException, nothing persisted to memory")
    void loopCapThrows() {
        stubSession();
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCallResponse());
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult(List.of(new UserMessage("u"))));

        assertThatExceptionOfType(AgentLoopLimitException.class)
                .isThrownBy(() -> service(2).advise(SESSION_ID, "Who should I pick?"));

        // iterations 1 and 2 executed; the third increment breached the cap BEFORE executing
        verify(toolCallingManager, times(2)).executeToolCalls(any(), any());
        assertThat(memory.get(String.valueOf(SESSION_ID))).isEmpty();
    }

    @Test
    @DisplayName("memory contract: exactly the user turn + final advice, no tool traffic, no system message")
    void memoryPersistsOnlyUserAndAdvice() {
        stubSession();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(toolCallResponse(), textResponse("Take Barkley."));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult(List.of(new UserMessage("u"))));

        service(8).advise(SESSION_ID, "Who should I pick?");

        List<Message> window = memory.get(String.valueOf(SESSION_ID));
        assertThat(window).hasSize(2);
        assertThat(window.get(0)).isInstanceOf(UserMessage.class);
        assertThat(window.get(0).getText()).isEqualTo("Who should I pick?");
        assertThat(window.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(window.get(1).getText()).isEqualTo("Take Barkley.");
    }

    @Test
    @DisplayName("second turn rides the memory: prior advice precedes the new user message")
    void secondTurnCarriesPriorConversation() {
        stubSession();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(textResponse("Take Barkley."), textResponse("Stick with him."));

        DraftAgentService service = service(8);
        service.advise(SESSION_ID, "Who should I pick?");
        service.advise(SESSION_ID, "Are you sure?");

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        List<Message> secondTurn = prompts.getAllValues().get(1).getInstructions();
        assertThat(secondTurn).hasSize(4);
        assertThat(secondTurn.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(secondTurn.get(1).getText()).isEqualTo("Who should I pick?");
        assertThat(secondTurn.get(2).getText()).isEqualTo("Take Barkley.");
        assertThat(secondTurn.get(3).getText()).isEqualTo("Are you sure?");
    }

    @Test
    @DisplayName("blank message -> InvalidDraftRequestException before any model call")
    void blankMessageRejected() {
        assertThatExceptionOfType(InvalidDraftRequestException.class)
                .isThrownBy(() -> service(8).advise(SESSION_ID, "   "));

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("unknown session -> DraftSessionNotFoundException (404)")
    void unknownSession() {
        when(sessionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(DraftSessionNotFoundException.class)
                .isThrownBy(() -> service(8).advise(99L, "Who should I pick?"));
    }
}
