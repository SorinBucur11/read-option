package app.readoption.agent;

import app.readoption.agent.dto.AdviceResponse;
import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigNotFoundException;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftService;
import app.readoption.draft.DraftSession;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.DraftSessionRepository;
import app.readoption.draft.InvalidDraftRequestException;
import app.readoption.news.PlayerNewsSearchService;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.ScoringRules;
import app.readoption.team.TeamContextService;
import app.readoption.valuation.DraftBoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The manual agent loop: {@code ChatModel} + {@code ToolCallingManager} — we own
 * the {@code while}, the iteration cap, and the per-round-trip instrumentation
 * that advisor-driven execution hides. In Spring AI 2.0 ChatModels never execute
 * tools internally, so external execution needs no opt-out flag; this is the
 * documented user-controlled {@code DefaultToolCallingManager} loop (advisor
 * adoption is a deferred design question, do not "modernize" this to
 * {@code ChatClient} + {@code ToolCallingAdvisor}).
 *
 * <p><b>No transaction here</b> — the loop holds LLM calls; each tool opens its own
 * short read-only transaction inside the 4.1 services (READ → REASON, no txn).
 * Memory persists only the user turn and the final advice; intra-loop tool traffic
 * is never persisted.
 */
@Service
public class DraftAgentService {

    private static final Logger log = LoggerFactory.getLogger(DraftAgentService.class);

    private final ChatModel chatModel;
    private final ToolCallingManager toolCallingManager;
    private final ChatMemory chatMemory;
    private final AgentPromptBuilder promptBuilder;
    private final AgentProperties properties;
    private final DraftSessionRepository sessionRepository;
    private final LeagueConfigRepository leagueConfigRepository;
    private final DraftService draftService;
    private final DraftBoardService draftBoardService;
    private final ProfileScoringService profileScoringService;
    private final PlayerRepository playerRepository;
    private final PlayerProjectionRepository projectionRepository;
    private final DraftPickRepository draftPickRepository;
    private final TeamContextService teamContextService;
    private final PlayerNewsSearchService newsSearchService;
    private final int currentSeason;

    public DraftAgentService(ChatModel chatModel,
                             ToolCallingManager toolCallingManager,
                             ChatMemory chatMemory,
                             AgentPromptBuilder promptBuilder,
                             AgentProperties properties,
                             DraftSessionRepository sessionRepository,
                             LeagueConfigRepository leagueConfigRepository,
                             DraftService draftService,
                             DraftBoardService draftBoardService,
                             ProfileScoringService profileScoringService,
                             PlayerRepository playerRepository,
                             PlayerProjectionRepository projectionRepository,
                             DraftPickRepository draftPickRepository,
                             TeamContextService teamContextService,
                             PlayerNewsSearchService newsSearchService,
                             @Value("${readoption.current-season}") int currentSeason) {
        this.chatModel = chatModel;
        this.toolCallingManager = toolCallingManager;
        this.chatMemory = chatMemory;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
        this.sessionRepository = sessionRepository;
        this.leagueConfigRepository = leagueConfigRepository;
        this.draftService = draftService;
        this.draftBoardService = draftBoardService;
        this.profileScoringService = profileScoringService;
        this.playerRepository = playerRepository;
        this.projectionRepository = projectionRepository;
        this.draftPickRepository = draftPickRepository;
        this.teamContextService = teamContextService;
        this.newsSearchService = newsSearchService;
        this.currentSeason = currentSeason;
    }

    /**
     * One advice turn. Advice is read-only, so any session status is allowed —
     * only {@code recordPick} requires ACTIVE.
     */
    public AdviceResponse advise(long sessionId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new InvalidDraftRequestException("message must not be blank");
        }
        DraftSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DraftSessionNotFoundException(sessionId));
        LeagueConfig config = leagueConfigRepository.findById(session.getLeagueConfigId())
                .orElseThrow(() -> new LeagueConfigNotFoundException(session.getLeagueConfigId()));
        ScoringRules rules = config.toScoringRules();

        // sessionId and rules are bound server-side on the per-request tools instance —
        // the model's tool schema has no field for either.
        DraftAgentTools tools = new DraftAgentTools(sessionId, rules, currentSeason,
                draftService, draftBoardService, profileScoringService,
                playerRepository, projectionRepository, draftPickRepository,
                teamContextService, newsSearchService);
        // 2.0's SDK-backed Anthropic module does NOT merge prompt options with the
        // configured defaults: a non-AnthropicChatOptions prompt options instance is
        // DISCARDED wholesale (blank fallback — no tools on the wire), and defaults
        // apply only when the prompt carries no options at all. ChatClient replicates
        // the merge itself (defaults.mutate().combineWith(request)); this bare
        // ChatModel loop must do the same: derive FULL provider-typed options from
        // the configured defaults (spring.ai.anthropic.chat.* — max-tokens,
        // temperature), then bind the per-request tool surface. The cast is safe:
        // this service only exists wired to the Anthropic starter.
        AnthropicChatOptions options = ((AnthropicChatOptions) chatModel.getOptions())
                .mutate()
                .model(properties.model())
                .toolCallbacks(ToolCallbacks.from(tools))
                .build();

        String conversationId = String.valueOf(sessionId);
        UserMessage userTurn = new UserMessage(userMessage);
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(
                promptBuilder.build(rules, config.toLeagueSettings(), config.getTactics(),
                        session.getTeamCount(), session.getTotalRounds())));
        messages.addAll(chatMemory.get(conversationId));
        messages.add(userTurn);

        long started = System.nanoTime();
        TokenTally tally = new TokenTally();
        int iteration = 0;

        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = instrumentedCall(prompt, iteration, tally);
        while (response.hasToolCalls()) {
            if (++iteration > properties.maxIterations()) {
                throw new AgentLoopLimitException(sessionId, properties.maxIterations());
            }
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            // conversationHistory() already carries system + user + assistant tool
            // calls + tool results — never re-add the system message.
            prompt = new Prompt(result.conversationHistory(), options);
            response = instrumentedCall(prompt, iteration, tally);
        }

        String advice = adviceText(response);
        // Persist ONLY the user turn + final advice; tool traffic stays out of memory.
        chatMemory.add(conversationId, List.of(userTurn, new AssistantMessage(advice)));

        long latencyMs = (System.nanoTime() - started) / 1_000_000;
        log.info("Draft advice session {}: {} tool iterations, {} total tokens, {} ms",
                sessionId, iteration, tally.total, latencyMs);
        return new AdviceResponse(advice, iteration, tally.total, latencyMs);
    }

    private ChatResponse instrumentedCall(Prompt prompt, int iteration, TokenTally tally) {
        long start = System.nanoTime();
        ChatResponse response = chatModel.call(prompt);
        long ms = (System.nanoTime() - start) / 1_000_000;

        long in = 0;
        long out = 0;
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage != null) {
            in = zeroIfNull(usage.getPromptTokens());
            out = zeroIfNull(usage.getCompletionTokens());
            tally.total += zeroIfNull(usage.getTotalTokens());
        }
        tally.in += in;

        // Tool names + inputs per round trip: token growth stays ATTRIBUTABLE to
        // specific calls — the observability default execution could not give.
        log.debug("iter {} | hasToolCalls={} | tools={} | in={} out={} | cumulative_in={} | {} ms",
                iteration, response.hasToolCalls(), requestedTools(response), in, out,
                tally.in, ms);
        return response;
    }

    private List<String> requestedTools(ChatResponse response) {
        return response.getResults().stream()
                .map(Generation::getOutput)
                .flatMap(message -> message.getToolCalls().stream())
                .map(toolCall -> toolCall.name() + toolCall.arguments())
                .toList();
    }

    private String adviceText(ChatResponse response) {
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null
                || generation.getOutput().getText() == null) {
            throw new IllegalStateException("model returned no advice text");
        }
        return generation.getOutput().getText();
    }

    private long zeroIfNull(Integer tokens) {
        return tokens == null ? 0 : tokens;
    }

    /** Cumulative token spend across the loop's model calls. */
    private static final class TokenTally {
        long in;
        long total;
    }
}
