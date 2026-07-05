# Phase 4.2 — Conversational Draft Agent (product build spec)

**Repo:** read-option · **Package:** `app.readoption.agent` · **Spring AI:** 1.1.8 (pinned — see API note)
**Depends on:** Phase 4.1 (`draft/`, `valuation/`), Phase 3 (`league_config`, `customization/`, `scoring/`)

This spec is the transient task layer. Standing conventions live in root `CLAUDE.md`
(Persistable upsert, `@Builder.Default`, no-FK-on-derived/config, RestClient idiom, the
spec→resolver→domain boundary, DTO page serialization). Follow those; this document only
covers what is new.

---

## 0. Non-negotiable API note (Spring AI 1.1.8 — use EXACTLY this, do NOT use 2.0 API)

User-controlled tool execution in 1.1.x is driven by `ChatModel` + `ToolCallingManager` with
`internalToolExecutionEnabled(false)` on `ToolCallingChatOptions`. **This option is REMOVED in
Spring AI 2.0** (replaced by `AdvisorParams.toolCallingAdvisorAutoRegister(false)`). The project
is on 1.1.8; the 2.0 API will not compile here. Do not "modernize" it.

Verified 1.1.8 pattern (resolve exact import packages against the 1.1.8 jars on the classpath):

```java
ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

ChatOptions chatOptions = ToolCallingChatOptions.builder()
        .toolCallbacks(ToolCallbacks.from(draftAgentTools)) // @Tool POJO -> ToolCallback[]
        .internalToolExecutionEnabled(false)                // WE own the loop
        .build();

Prompt prompt = new Prompt(messages, chatOptions);          // messages: List<Message>
ChatResponse response = chatModel.call(prompt);

while (response.hasToolCalls()) {
    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
    prompt = new Prompt(result.conversationHistory(), chatOptions); // history carries system+user+toolcalls+toolresults
    response = chatModel.call(prompt);
}
String advice = response.getResult().getOutput().getText();
```

Key facts baked into this pattern:
- `chatModel.call(prompt)` returns a `ChatResponse`; `response.hasToolCalls()` is the loop condition.
- `toolCallingManager.executeToolCalls(prompt, response)` executes ALL tool calls in the response
  (parallel calls included) and returns `ToolExecutionResult`; `result.conversationHistory()` is the
  full running `List<Message>` (system + user + assistant-with-tool-calls + tool-response messages).
- The next `Prompt` is built PURELY from `conversationHistory()` — the system message assembled into
  the first prompt carries forward automatically; do NOT re-add it each iteration.
- Token usage per call: `response.getMetadata().getUsage()` →
  `getPromptTokens()` / `getCompletionTokens()` / `getTotalTokens()`.
  (If `getCompletionTokens()` does not resolve on 1.1.8, it is `getGenerationTokens()`.)
- Tools are `@Tool`-annotated methods; schema is generated from the method signature.
  `@ToolParam` params are required by default; nullable → optional.

Inject `ChatModel` (autoconfigured by `spring-ai-starter-model-anthropic`). Do NOT use `ChatClient`
for the agent loop — `ChatClient` default execution hides the loop; the whole point of this build is
to own it.

---

## 1. Package layout (new files under `app.readoption.agent`)

```
agent/
  DraftAgentTools.java          # @Tool methods (read-only); per-request instance
  DraftAgentService.java        # the manual loop + memory + instrumentation
  DraftAgentController.java     # POST /api/draft/sessions/{id}/advise
  AgentProperties.java          # @ConfigurationProperties(prefix="readoption.agent")
  AgentPromptBuilder.java       # assembles the pre-injected system prompt
  ProfileScoringService.java    # NEW: per-player history+projection scored under resolved rules
  dto/
    AdviceRequest.java          # { String message }
    AdviceResponse.java         # { String advice, int iterations, long totalTokens, long latencyMs }
```

No migration. No entity changes. This increment is read-only over the 4.1/Phase-3 substrate plus
one new scoring assembly service.

---

## 2. Architecture decisions (locked — implement as stated)

1. **Manual loop, user-controlled execution.** `ChatModel` + `ToolCallingManager`,
   `internalToolExecutionEnabled(false)`. We own the `while`, the `MAX_ITERATIONS` cap, and the
   per-iteration instrumentation. Rationale: default execution hides token growth, round-trip
   count, and the loop cap — the exact observability an on-the-clock advisor needs.

2. **Three read-only tools; static facts pre-injected.** Dynamic, draft-draining facts (state,
   board, player profiles) are tools. Snapshot facts (league scoring summary, user `DraftTactics`)
   are pre-injected into the system prompt once — never a tool call. No `record_pick` tool in 4.2
   (mutation via agent is a later increment with confirmation; the human records via the existing
   `POST /picks`).

3. **Session-owned everything.** `sessionId` is the conversation id for memory AND the scope for
   every tool. It is NEVER a tool parameter and NEVER model-supplied — it is bound server-side on
   the per-request `DraftAgentTools` instance. Same for the resolved `ScoringRules`: bound on the
   tools instance, so the profile tool scores under the league's real rules with no format default.

4. **Manual memory, tool traffic excluded.** `MessageWindowChatMemory` keyed on `sessionId`. Load
   the window before the loop; persist ONLY the new user message + the final assistant advice after.
   Intra-loop tool request/response messages are NOT persisted.

---

## 3. `DraftAgentTools` (read-only, per-request instance)

A plain object (NOT a Spring bean) constructed per advice request, holding the Java-owned session
facts plus references to the injected singleton 4.1 services. `sessionId` and `scoringRules` are
FIELDS, never `@ToolParam` — the generated schema must give the model no way to address another
session or rescore under another format.

```java
public class DraftAgentTools {

    private final long sessionId;            // Java-owned: field, never a @ToolParam
    private final ScoringRules scoringRules;  // Java-owned: field, never a @ToolParam
    private final DraftService draftService;
    private final DraftBoardService draftBoardService;
    private final ProfileScoringService profileScoringService;

    // constructor sets all fields

    @Tool(description = "Get the current draft state: overall pick on the clock, which team is "
            + "picking, the user's roster so far, unfilled roster slots, picks until the user's "
            + "next turn, and per-opponent positional counts for the teams picking before the "
            + "user's next turn (the survivability gap).")
    public DraftStateView getDraftState() {
        return draftService.getState(sessionId);
    }

    @Tool(description = "Get the VORP-ranked board of available (undrafted) players, scored under "
            + "this league's rules. Each row: playerId, name, position, projected points, VORP, and "
            + "this format's ADP. Optionally filter by position (QB/RB/WR/TE) and limit rows "
            + "(default 20, max 50). All points are this league's scoring - never compare against "
            + "other formats.")
    public List<PlayerValue> getDraftBoard(
            @ToolParam(required = false, description = "Optional position filter: QB, RB, WR, or TE")
            String position,
            @ToolParam(required = false, description = "Max rows to return (default 20, max 50)")
            Integer limit) {
        return draftBoardService.board(sessionId, position, limit); // reuse the 4.1 board path
    }

    @Tool(description = "Get one player's detailed profile by playerId (from the board): the last "
            + "five seasons of actual fantasy points (total, per-game, games played) plus the "
            + "current-season projection with ADP and positional rank. All points are scored under "
            + "THIS league's rules - do not compare against other formats.")
    public PlayerProfileView getPlayerProfile(
            @ToolParam(description = "The playerId from the draft board")
            long playerId) {
        return profileScoringService.profile(playerId, scoringRules);
    }
}
```

- Return DTOs directly (records/views). Spring AI serializes tool return values to JSON via
  `DefaultToolCallResultConverter` — return the existing `DraftStateView` / `PlayerValue` /
  the new `PlayerProfileView`. Keep them compact (they were designed as the tool-result dry run).
- `DraftBoardService` needs a `board(sessionId, position, limit)` method if one is not already
  extractable from the 4.1 controller path — reuse the exact scoring/exclusion/ADP logic the
  `GET /board` endpoint uses; do NOT duplicate it. If the logic currently lives in the controller,
  extract it to the service first (own commit).

---

## 4. `ProfileScoringService` (NEW — resolves the "profile under resolved rules" carry item)

The existing `GET /api/players/{id}/profile` scores under a `ScoringFormat` **preset** (with a
`STANDARD_6PT` default). The agent must NOT ride that endpoint: a default format silently produces
plausible-but-wrong numbers next to a board scored under the league's real rules (loud-failure
violation — the quiet cousin of the teamCount bug). This service scores a player's history and
projection under the session's resolved `ScoringRules`, no default, rules required.

```java
public PlayerProfileView profile(long playerId, ScoringRules rules) {
    // 1. load player (404 -> PlayerNotFoundException, reuse existing)
    // 2. for each season's player_stats StatLine: ScoringService.calculate(statLine, rules, position)
    //    -> per-year { year, totalPoints, pointsPerGame, gamesPlayed }
    // 3. score the 2026 projection line under the same rules; attach ADP + positionalRank
    //    (ADP from the format-matched column via AdpBucket.forReceptionPoints(rules), same as the board)
    // 4. return PlayerProfileView { playerId, name, position, team, history[], projection{...} }
}
```

- `ScoringService.calculate(StatLine, ScoringRules, Position)` already exists — reuse it; never
  originate a number here, only assemble scored lines.
- Regression anchors must still hold under standard rules: Barkley 4866 (208.50/226.00/243.50 across
  formats), Mahomes 4046 (−2/INT). Add a test asserting a TE-premium `ScoringRules` scores a TE's
  history line higher than the standard rules do (the same uniform-path proof the board has).

---

## 5. `AgentPromptBuilder` (pre-injected static context)

Builds the system message once per request from snapshot facts. NOT tools.

Contents:
- Role + behavior: fantasy draft advisor; always consult tools before advising; never originate a
  number; recommend a specific pick and justify via VORP, positional scarcity, roster needs, and
  survivability (picks until next turn + gap-team positional counts).
- **Pre-injected league scoring summary** (from resolved `ScoringRules` / `ScoringFormat` — a short
  human-readable line, e.g. "Half-PPR, TE premium 1.5, 12 teams, 15 rounds").
- **Pre-injected `DraftTactics`** (Phase 3 strategic priors from `league_config.tactics`) as
  standing user preferences the advisor should weigh.
- **Output-shape constraint (carry item):** concise, plain prose suited to reading on the clock; a
  short recommendation with 2-4 reasons; NO decorative markdown, NO emoji headers, NO large tables
  unless explicitly asked. (The spike returned heavy emoji-markdown — wrong for a fast turn.)
- **Honest-starvation instruction:** if information the advice needs is unavailable through the
  tools (player roles, depth charts, injuries, news), say so explicitly rather than guessing.

The roster and unfilled slots are DYNAMIC — they come from `get_draft_state`, not pre-injection.

---

## 6. `DraftAgentService.advise(long sessionId, String userMessage)` — the loop

```
1. Load session (DraftSessionNotFoundException 404) and its league_config
   -> resolvedRules = leagueConfig.toScoringRules(); leagueSettings = toLeagueSettings(); tactics.
   (Snapshot facts; loaded once per request. Session must be ACTIVE or advice still allowed?
    -> advice is READ-ONLY, so allow advice on any status; only recordPick requires ACTIVE.)
2. tools = new DraftAgentTools(sessionId, resolvedRules, draftService, draftBoardService, profileScoringService)
3. systemMessage = agentPromptBuilder.build(resolvedRules, leagueSettings, tactics)
4. history = memory.get(String.valueOf(sessionId))         // prior turns, may be empty
5. messages = [systemMessage] + history + [new UserMessage(userMessage)]
6. chatOptions = ToolCallingChatOptions.builder()
                    .toolCallbacks(ToolCallbacks.from(tools))
                    .internalToolExecutionEnabled(false)
                    .build()
7. prompt = new Prompt(messages, chatOptions)
8. LOOP (instrumented):
      iteration = 0; cumulativeInputTokens = 0
      response = chatModel.call(prompt)
      log iteration metrics (see section 7)
      while (response.hasToolCalls()):
          if (++iteration > MAX_ITERATIONS) throw AgentLoopLimitException  // loud cap
          result = toolCallingManager.executeToolCalls(prompt, response)
          prompt = new Prompt(result.conversationHistory(), chatOptions)
          response = chatModel.call(prompt)
          log iteration metrics
      advice = response.getResult().getOutput().getText()
9. Persist to memory ONLY the user turn + final advice (NOT the tool traffic):
      memory.add(String.valueOf(sessionId), List.of(new UserMessage(userMessage),
                                                    new AssistantMessage(advice)))
10. return AdviceResponse(advice, iteration, cumulativeTotalTokens, totalLatencyMs)
```

- `MAX_ITERATIONS` from `AgentProperties` (default 8, same as the raw loop). Exceeding it throws
  `AgentLoopLimitException` (500-class, loud) — never silently return a partial answer.
- `MessageWindowChatMemory` bean: `MessageWindowChatMemory.builder().maxMessages(agentProps.getMemoryWindow()).build()`
  (default window e.g. 20). The default `InMemoryChatMemoryRepository` is fine for 4.2 (a draft is a
  single sitting); JDBC-backed memory is deferred.

---

## 7. Instrumentation (the reason for the manual loop — restores what the spike lost)

Per model round trip, log at DEBUG (and accumulate for the response):
```
iter <n> | hasToolCalls=<bool> | tools=[<names>] | in=<promptTokens> out=<completionTokens>
        | cumulative_in=<sum> | <latencyMs> ms
```
- Log the tool names/inputs the model requested each iteration (from `response`), so token growth is
  ATTRIBUTABLE to specific calls — the thing default execution could not give.
- Accumulate cumulative input tokens across iterations; return total tokens + total latency in
  `AdviceResponse`.
- This is deliberately the observability the Part-2 spike proved was missing. Do not remove it.

Also add request logging on the tool path is now moot (tools call services in-process, no HTTP) —
the iteration log IS the both-ends visibility for this build.

---

## 8. Controller

```
POST /api/draft/sessions/{id}/advise
  body: AdviceRequest { message }
  200:  AdviceResponse { advice, iterations, totalTokens, latencyMs }
  404:  session not found (existing DraftSessionNotFoundException)
  400:  blank message (InvalidDraftRequestException, reuse)
```

RFC 9457 ProblemDetail via the existing `GlobalExceptionHandler`. Register `AgentLoopLimitException`
there (500, no leak of internals).

---

## 9. Tests (risk-based, existing four slice patterns)

**Unit (mocked `ChatModel`) — the loop is the load-bearing logic:**
- `DraftAgentServiceTest`: mock `ChatModel` to return a scripted sequence — (a) a response WITH tool
  calls, then (b) a response with none — assert the loop calls `executeToolCalls` exactly once, then
  terminates and returns the final text. Use a real `ToolCallingManager` with a stub tools object, or
  mock the manager and assert `conversationHistory()` is fed into the next prompt.
- Loop cap: script `MAX_ITERATIONS+1` tool-call responses → assert `AgentLoopLimitException`, and that
  it is thrown (loud), not swallowed.
- Memory contract: after an advice call, assert the memory window for the session contains exactly the
  user message + final assistant message, and NO tool request/response messages.
- Session-owned scope: assert `DraftAgentTools` exposes no `sessionId`/`format` schema field
  (the generated `ToolCallback` definitions have only the documented params).

**Service (real math, never mocked):**
- `ProfileScoringServiceTest`: real `ScoringService`; standard-rules anchors hold (Barkley 4866,
  Mahomes 4046 −2/INT); a TE-premium `ScoringRules` scores a TE history line higher than standard
  (uniform-path proof, mirroring `DraftBoardServiceTest`).

**@WebMvcTest:**
- `DraftAgentControllerTest`: mock `DraftAgentService`; 200 shape, 404 on unknown session, 400 on
  blank message.

**No new @DataJpaTest** — this increment adds no persistence.

225+ existing tests must stay green.

---

## 10. Acceptance runbook (manual, after build)

Prereq: read-option running, an ACTIVE draft session mid-draft (session 4 shape: team 8 of 12,
~11 picks in), league resolves to a known format.

1. `POST /sessions/4/advise {"message":"Who should I pick right now?"}` → advice names a specific
   player with VORP/scarcity/survivability reasoning; `iterations` ≥ 1; the DEBUG log shows the
   per-iteration token/tool lines. Confirm the numbers (VORP, ADP) match the board (curl `/board`).
2. `{"message":"Compare the two best available RBs in detail."}` → the log shows a profile-fetch
   iteration; the compared numbers are scored under the LEAGUE'S rules (not STANDARD_6PT). Verify one
   player's profile numbers against `ProfileScoringService` output for the resolved rules.
3. Second message in the same session → prior advice is in context (memory works); the loop does NOT
   re-explain from scratch.
4. Kill read-option's DB mid-advice (or force a tool error) → the advice degrades honestly ("I can't
   read the board right now"), the loop does not crash, and `is_error`-style tool results re-enter the
   model (the manager surfaces tool exceptions as tool responses by default; confirm).
5. Confirm honest starvation: prompt 2's "wait or reach" reasoning explicitly flags missing
   roles/depth/injury data — the verbatim requirements list for 4.3/4.4.

---

## 11. Deferred (add to project instructions DEFERRED list on phase closure)

- **`record_pick` as an agent tool** — mutation via the agent, with a confirmation step. 4.2 agent is
  read-only by design.
- **JDBC-backed `ChatMemory`** — survive restarts / multi-instance. In-memory is fine for a single
  draft sitting.
- **Boot 4 + Spring AI 2.0 migration** — note that THIS build's `internalToolExecutionEnabled(false)`
  is 1.1.x-only; the 2.0 migration must rewrite the loop to `AdvisorParams.toolCallingAdvisorAutoRegister(false)`
  (a named migration increment, not a bump).

---

## 12. Commit plan (separate commits, per the workflow routing rule)

1. `DraftBoardService.board(...)` extraction (if the board logic is currently in the controller) —
   pure refactor, no behavior change, tests green before and after.
2. `ProfileScoringService` + `PlayerProfileView` + tests — the new scoring assembly.
3. The agent core: `DraftAgentTools`, `DraftAgentService`, `AgentPromptBuilder`, `AgentProperties`,
   controller, DTOs, exception, tests.

Review each diff against this spec as a checklist — one finding per item, diff evidence, closed-or-open.
