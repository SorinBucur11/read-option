# Phase 4 — AI Draft Assistant: Review / Walkthrough

This document describes what is actually built across Phase 4 (`app.readoption.draft`,
`.valuation`, `.agent`, `.team`, `.news`) as of the Phase 4.4.2 close. It is a
walkthrough of the code, not a restatement of the specs; where the code and the
`docs/specs/phase-4-*` specs diverge, the code is described and the divergence is
flagged in [Drift from spec](#11-drift-from-spec). Its sibling for the previous
phase is [phase-3-overview.md](phase-3-overview.md).

---

## 1. What Phase 4 is

Phase 4 turns the scored, reconciled, league-customized data of Phases 1–3 into a
**conversational draft assistant**: an agent you talk to while on the draft clock,
which answers with a recommendation grounded in a VORP-ranked board, the live
draft state, depth charts, injuries, byes, and a retrieved news corpus.

The phase centerpiece is an architectural inversion. Phases 2–3 were
**pre-injection RAG**: Java decided what the model needed, fetched it, and
injected it into one call. Phase 4 is **agentic tool calling**: the model decides
mid-reasoning what it needs and requests it by emitting a structured call against
a schema the Java code publishes; the Java code executes the call and returns the
result; the model never runs anything. The house boundary survives the inversion —
**data providers predict → the Java engine scores, ranks, reconciles → the LLM
classifies/strategizes** — the model went from *receiving* facts to *requesting*
them, and it still never originates a number. Every point total, VORP, ADP, depth
order, and date the agent quotes is a verbatim tool result.

### Increment map

| Increment | What landed | Commits |
|---|---|---|
| **4.1** — Draft domain + value engine | Per-format ADP promotion (V11), draft sessions/picks (V12), snake-order arithmetic, replacement-level/VORP engine, draft board. Zero LLM code, by design. | `76c96e3` |
| **4.2** — The agent loop | Manual `ChatModel` + `ToolCallingManager` loop, three session-bound tools, windowed memory, `POST /advise`, per-iteration instrumentation. | `aebd3d7`, `35ba3d6` |
| **4.3** — Current-context ingestion | `nfl_team` + `team_schedule` (V13/V14), ESPN schedule sync, five depth-chart/injury columns on `player`, role/injury/bye enrichment flowing through the *existing* tools. | `1ca5956`…`cf1b395` |
| **4.3.1** — Tool graduation | `findPlayer` and `getTeamContext` graduated from the deferred list on transcript evidence; pair-gated role degradation. | `2e31d96` |
| **4.M** — The migration | Java 17→21, Spring Boot 3.5→4.0, Spring AI 1.1→2.0 (official-SDK-backed Anthropic), Jackson 3, Testcontainers 2.x; the F-12 options regression found and fixed at the wire. | `940dcde`…`7a76a0c` |
| **4.4** — Vector news RAG | `player_news` landing (V15), `news_embedding` pgvector table (V16), OpenAI embedding build, `searchPlayerNews` as the sixth tool. | `db5a966`…`fb8508c` |
| **4.4.1** — Association fix | `player_news` PK widened to `(source, news_id, player_id)` (V17); embedding id gains playerId. | `7250418` |
| **4.4.2** — Experience exposure | Java-derived experience label on the player profile; the rookie-scoping prompt discipline. | `7310f35` |

Ordering rationale: the graduation rule applied to *data* — build a retrieval
increment only when a consumer has demonstrated the need. The 4.2 agent was
deliberately let loose **starved** (three tools, no team/news context), its
failure transcripts were collected, and those transcripts *wrote the
requirements* for 4.3, 4.3.1, and 4.4. Each later tool exists because a specific
transcript showed the agent needing it.

---

## 2. Concepts

### VORP — value over replacement player

`VORP = projected points − replacement level(position)`, where the replacement
level is the points of the best player *outside* the league-wide startable pool.
Raw projections rank production; VORP ranks **scarcity-adjusted value**. A
340-point QB can be worth less than a 260-point RB because in a 1-QB league the
11th QB is nearly free while the 21st RB is not.

The startable pool is a pure function of the confirmed league's roster shape
(this is where the Phase 3 config finally does load-bearing work):

1. Reserve dedicated starters per position: `teamCount × slots(position)`.
2. Greedily absorb FLEX slots over the flex-eligible set (best remaining points).
3. Greedily absorb SUPERFLEX slots over flex-eligible ∪ QB.
4. Replacement level = the best player left outside the reserved pool. A
   position too shallow degrades to `0` with a WARN, never a throw.

Bench slots deliberately do **not** extend the baseline — the baseline is
starters-only; bench value is option value left to the LLM to reason about. The
board's replacement levels are a **static pre-draft baseline**: the board drains
as players are picked, but season-long scarcity doesn't, so the levels are not
recomputed over the drained pool.

The arithmetic lives in `ReplacementLevelCalculator` (pure static, no Spring, no
I/O — the `DispersionCalculator` idiom). `DraftBoardService` scores the entire
projection mart **in memory** under the league's resolved rules on one uniform
path (~400 `ScoringService.calculate` calls per request — trivial, and
precomputation hasn't earned a read path), subtracts the levels, filters drafted
players, and sorts by VORP desc → ADP asc (nulls last) → playerId.

### Snapshot vs input

`draft_session` freezes `team_count` and `total_rounds` from the league config at
session creation — the client cannot supply a value the config already owns, and
a later config edit cannot change a running draft's length. Like an FX rate
frozen on a booked trade: the derivation's *input* can change mid-draft, so the
derived value is captured as a fact. The same principle polices the prompt
layer: `AgentPromptBuilder` prints team count and rounds from the **session
snapshots**, never re-derived from config, with a regression test whose fixture
deliberately makes the two sources disagree.

### The server owns the sequence

A pick request carries only `playerId`. `overall_pick_no` is assigned
server-side (max + 1, inside the transaction) — a client-supplied sequence
number invites desync and races. The composite PK `(session_id,
overall_pick_no)` is the concurrency backstop, and `UNIQUE (session_id,
player_id)` is the final arbiter of can't-draft-twice: the service pre-check
produces the friendly 409 (naming the pick that took the player), the constraint
guarantees the invariant under races or a future second writer. Snake-draft team
assignment is **derived, not stored** — `SnakeOrder` computes round/team/next
turn from `(overall_pick_no, team_count)`; persisting a derivation invites drift
from its source, and the javadoc documents the *absence* of the column so it
reads as a decision, not an oversight.

### The manual agent loop (user-controlled execution)

`DraftAgentService` runs the loop by hand — `ChatModel` + `ToolCallingManager`,
deliberately **not** `ChatClient` + advisor auto-execution (the class javadoc
forbids "modernizing" it). The framework keeps what it is good at (JSON-schema
generation from method signatures, tool dispatch); the application takes back
the `while` loop, because loop caps, per-iteration token growth, and latency
budgets must be observable when advice happens on the draft clock:

```
call model
while (response.hasToolCalls()):
    iteration++            → over the cap? throw AgentLoopLimitException (500)
    toolCallingManager.executeToolCalls(prompt, response)
    prompt = rebuild from result.conversationHistory()
    call model
return final text
```

Every round trip logs tool names + raw model-supplied args, input/output tokens,
and latency at DEBUG; the response carries `iterations`, `totalTokens`,
`latencyMs`. **No transaction anywhere near the loop** — each tool opens its own
short read-only transaction inside the domain services. A transaction wrapped
around the loop would pin a DB connection across multi-second LLM round trips —
the same anti-pattern as a transaction spanning a remote call in a payment flow.

Tool failure is honest degradation, not a crash: the tool manager converts a
thrown tool exception into an error tool-response and the loop continues. In the
live database-kill drill, one tool failed, the next succeeded on a recovered
connection, and the advice opened with "I can't pull a live update," fell back
to prior context, and flagged the pick as unconfirmed — loud failure at the
reasoning layer, under *partial* tool failure.

### The field-vs-parameter safety boundary

`DraftAgentTools` is a **per-request POJO, never a Spring bean**. `sessionId`,
the resolved `ScoringRules`, and the current season are constructor-bound Java
fields — so the generated tool schema has **no parameter** through which the
model could address another session or rescore under another format. This is a
regression-tested safety property: a test parses the emitted JSON schemas and
asserts each of the six tools exposes exactly its documented parameters and
nothing else. Server-side knobs stay server-side the same way: news `topK` and
the embedding-generation tag are configuration, not tool parameters.

### Pre-inject vs tool

Not every fact should be a tool — a tool call is a model round trip, and round
trips are latency paid on the clock. **Snapshot facts** (the league's resolved
scoring summary, the user's standing `DraftTactics`) are pre-injected once into
the system prompt; **dynamic facts** (draft state, board, profiles, team rooms,
news) are tools, because they change as the draft drains. The clean-run
transcript validated the split: the agent cited the user's Zero-RB strategy
without spending a tool call to fetch it.

### Memory

Session-keyed `MessageWindowChatMemory` (window 20), conversation id = the same
server-owned session id that scopes every tool. Only the user's question and the
final advice are persisted — the intra-loop tool traffic stays out (it would
bloat the window and leak tool schema into history), and a failed loop persists
nothing.

### The degradation vocabulary — loud strings, never silence

Every retrieval layer degrades to a **distinct, explicit label** rather than a
null, an empty list, or a silent remap: a free agent reads `NO_TEAM`, a missing
bye is a loud label, a player outside the ESPN crosswalk returns
`NEWS_UNAVAILABLE_NO_ESPN_ID` while a queryable player with a quiet corpus
returns `NO_NEWS_FOUND`, an unknown experience value reads
`EXPERIENCE_UNKNOWN`. Each vocabulary has one home (`TeamContextService`,
`NewsVocabulary`, the experience derivation). The tool descriptions instruct the
model to treat these as *unknown — say so, never guess*, and the transcripts
show it complying. Failure modes are designed to degrade toward noisy, not
toward convincing.

### Landing vs derived — the news RAG split

`player_news` is the **landing** table: insert-only, verbatim (HTML and all),
permanent — ESPN's retention is opaque (as few as 4–5 blurbs per star player),
so rolled-off items are unrecoverable and this table is the only durable
record. `news_embedding` is **derived**: disposable and rebuildable, keyed by a
deterministic UUID (`nameUUIDFromBytes("source:newsId:playerId:modelTag")`), so
the embed endpoint is an idempotent anti-join (embed only what the current model
generation lacks) and a provider swap is re-embed + config flip, never data
loss. Retrieval filters on `player_id` **and** the current
`embedding_model` tag, so old-generation vectors never surface.

Time-grounding is carried through every layer: each stored and retrieved item
keeps its publication date, and the tool description orders the model to state
the date when citing ("per a March 2 report") and to treat old reports as
possibly stale — news items are point-in-time reports, not current facts.

### The prompt governs composition, not retrieval (4.4.2)

The rookie-classification increment surfaced the sharpest conceptual lesson of
the phase. The board rows carry no experience field; the filtering fact lives
one lookup deep in the profile — so to answer "best rookies," the model *must*
retrieve the non-answers to find the answers, and everything retrieved into
context is candidate material at composition time. Rejected rows don't vanish
like a WHERE clause; they sit in working memory exerting pressure to be used.
The fix was layered: a Java-derived `experience` label (entry-year claims only
at `years_exp` 0/1, ordinal for veterans — the probe set proved `years_exp`
counts *accrued* seasons and freezes for out-of-league players), a grounding
line in the tool description (experience truth comes only from the profile
field), and rewording that removed the token "rookie" from every non-rookie
label — the sophomore label itself had been the lexical attractor. The
architectural lever (an experience flag on the board rows) is ledgered,
graduation-gated, not built.

---

## 3. The tool surface

Six read-only `@Tool` methods on `DraftAgentTools`. Mutation (`record_pick`) is
deliberately absent — the human reports picks over HTTP; the agent advises.

| Tool | Parameters | Returns | Added |
|---|---|---|---|
| `getDraftState` | — | `DraftStateView`: pick on the clock, user roster (with byes), unfilled slots, picks until next turn, per-opponent positional counts in the gap | 4.2 |
| `getDraftBoard` | `position?`, `limit?` (default 20, max 50) | `DraftBoardView`: VORP-ranked available players with projected points, VORP, format-matched ADP, replacement levels | 4.2 |
| `getPlayerProfile` | `playerId` | `PlayerProfileView`: 5 seasons of actuals + projection scored under *this league's* rules, ADP, positional rank, team/role/depth-chart-ahead, injury trio, bye, weeks 1–3 opponents, experience label | 4.2 (enriched 4.3, 4.4.2) |
| `findPlayer` | `name` (partial, case-insensitive) | up to 5 candidates: playerId, position, team, drafted-in-this-session (+ pick), has-projection | 4.3.1 |
| `getTeamContext` | `team`, `position?` | `TeamRoomView`: bye, early opponents, the depth-chart room at a position — each entry with playerId, order, injury label, drafted flag | 4.3.1 |
| `searchPlayerNews` | `playerId`, `query` (free text) | `PlayerNewsView`: up to 5 dated news items, reverse-chronological, or a loud degradation string | 4.4 |

Design details worth knowing:

- **Descriptions are an instruction surface, not documentation.** The board
  description warns that absence from the top-N is not "drafted" (and routes to
  `findPlayer`); the profile description pins the depth-chart legend ("LWR/RWR
  are outside receivers, SWR is the slot receiver" — one sentence that changed
  the model's reading of the same raw data), the format discipline ("never
  compare against other formats"), and the experience grounding rule; the news
  description mandates date-stated citations.
- `findPlayer` exists because the agent once fabricated a fact from a truncated
  board view; `getTeamContext`'s room entries carry `playerId` because the
  observed gap was literally "can't pull the handcuff's profile without the ID."
- Bad tool arguments throw `IllegalArgumentException`, which becomes an error
  tool-response the model can self-correct from — not a 500.

## 4. Endpoints

Phase 4 adds seven endpoints (the full API including Phases 1–3 is in the
README). All errors are RFC 9457 `ProblemDetail` via `GlobalExceptionHandler`;
Phase 4 additions to the problem vocabulary: `draft-session-not-found` (404),
`draft-session-not-active` (409, carries `status`), `player-already-drafted`
(409, carries `playerId` + `overallPickNo`), `invalid-draft-request` (400),
`agent-loop-limit` (500).

```
POST /api/draft/sessions                       { leagueConfigId, userSlot }
        Start a draft session against a confirmed league config. teamCount and
        totalRounds are NOT request fields — they are snapshotted from the
        config (totalRounds = sum of all roster slots). 201 with the session.

POST /api/draft/sessions/{id}/picks            { playerId }
        Record a pick (yours or an opponent's — all 130 picks matter). The
        server assigns overall_pick_no; the response derives round and teamSlot
        via snake arithmetic. 201; 409 player-already-drafted names the taking
        pick; the final pick flips the session COMPLETE in the same txn.

GET  /api/draft/sessions/{id}/state
        The compact live read model: current pick/team, on-the-clock flag,
        your roster (with byes), unfilled slots, picks until your next turn,
        per-opponent positional counts in the gap.

GET  /api/draft/sessions/{id}/board?position=RB&limit=20
        The VORP-ranked board of available players under this league's rules,
        with per-position replacement levels. limit 1..50, default 20.

POST /api/draft/sessions/{id}/advise           { message }
        One conversational turn with the draft agent (live Anthropic call,
        multi-iteration tool loop). Returns { advice, iterations, totalTokens,
        latencyMs }. Session-scoped memory recalls prior turns.

POST /api/news/sync
        Ingest ESPN Rotowire news for every QB/RB/WR/TE with an espn_id into
        the player_news landing table (idempotent, insert-only, several
        minutes). Returns { playersSynced, itemsInserted, itemsSkippedExisting,
        failed[] }.

POST /api/news/embed
        Derived build: anti-join player_news against the current embedding
        generation, embed the difference via OpenAI (chunked, bounded backoff).
        Returns { candidates, embedded, alreadyCurrent }. Deliberately separate
        from sync — ingestion never waits on a vendor.
```

Also Phase 4 (4.3): `POST /api/teams/schedule/sync?season=2026` — ESPN schedule
into `team_schedule` (delete-and-reload per team, hard regular-season filter,
WSH→WAS crosswalk at the write boundary, loud bye derivation). Returns
`{ season, teams, synced[], skipped[], failed[] }`.

## 5. What persists

Phase 4 migrations, V11–V17 (head at close: V17):

| Migration | Table(s) | What / why |
|-----------|----------|------------|
| V11 | `player_projection_raw`, `player_projections` | Per-format ADP: raw swaps single `adp`/`adp_format` for `adp_std`/`adp_half_ppr`/`adp_ppr` `NUMERIC(6,2)`; mart columns widened to match. ADP is an observed market fact — copied verbatim rotowire raw → mart on every write, never derived, never converted between formats. |
| V12 | `draft_session`, `draft_pick` | Sessions: IDENTITY id, frozen `team_count`/`total_rounds` snapshots (CHECKs 2–20 / 1–30, `user_slot ≤ team_count`), `status` the only mutable field. Picks: composite PK `(session_id, overall_pick_no)`, **real FKs** to session and player (true transactional child rows — unlike landing/config tables), `UNIQUE (session_id, player_id)`, no `team_no` column (derived). |
| V13 | `nfl_team`, `team_schedule`, `player` +5 cols | Teams: Sleeper abbrev is the canonical PK, `espn_abbrev` UNIQUE crosswalk, derived `bye_week`. Schedule: PK `(team, season, week)`, each game twice, no FK (landing). Player gains raw-vocabulary context columns: `depth_chart_position`, `depth_chart_order`, `injury_status`, `injury_body_part`, `injury_notes` — the depth chart is a *query* over these, not a table. |
| V14 | `nfl_team` seed | 32 rows, no stale OAK, WAS/WSH the single crosswalk divergence. DDL/DML split deliberate. |
| V15 | `player_news` | News landing: insert-only, verbatim, permanent; `published TIMESTAMPTZ` is the citation fact; typed `source_payload JSONB`; no FK, no `updated_at`. Index `(player_id, published DESC)`. |
| V16 | `news_embedding` (+ `CREATE EXTENSION vector`) | The pgvector table, `PgVectorStore`'s validated column set (`id UUID` deterministic, `content`, `metadata JSONB`, `embedding VECTOR(1536)`), HNSW cosine index. Flyway owns the DDL; the store bean boots with validation on, so schema drift fails at startup, not on a draft-day search. |
| V17 | `player_news` | PK widened to `(source, news_id, player_id)` — one item legitimately appears in several players' feeds (trades, signings); the V15 PK silently collapsed those associations (review R-1, Phase 4.4.1). |

Entity conventions hold: `@Enumerated(STRING)` everywhere, `Persistable` upsert
pattern on assigned-key tables (`DraftPick`, `NflTeam`, `TeamSchedule`,
`PlayerNews`), IDENTITY tables (`DraftSession`) deliberately not, composite keys
via `@IdClass`, JSONB via `@JdbcTypeCode(SqlTypes.JSON)`. `news_embedding` has
**no JPA entity** — it belongs to Spring AI's `PgVectorStore` (explicit bean:
table name pinned, `initializeSchema=false`, validation on,
`@DependsOnDatabaseInitialization` so it validates after Flyway).

## 6. Class map (Phase 4 packages)

| Class | Responsibility |
|---|---|
| **`valuation`** | |
| `ReplacementLevelCalculator` | Pure static greedy absorption → per-position replacement levels; no Spring, no I/O. |
| `DraftBoardService` | Scores the mart in memory under resolved rules, computes VORP, filters drafted, sorts, limits. |
| `PlayerValue`, `DraftBoardView` | Input record; board view (season, replacement levels, rows). |
| `AdpBucket` | Nearest-reception-bucket for custom rules; reproduces `ScoringFormat.adpBucket()` on all six presets. |
| **`draft`** | |
| `DraftSession`, `DraftPick(+Id)`, `DraftStatus` | The session (frozen snapshots) and the insert-only pick ledger. |
| `SnakeOrder` | Pure 1-based snake arithmetic: `teamFor`, `overallPickFor`, `nextPickFor`, `picksUntilNextTurn`. |
| `DraftService` | Session start (config load, slot check, rounds freeze), server-assigned picks, COMPLETE flip via dirty checking, constraint-name 409 translation, state view assembly. |
| `DraftController` | The four `/api/draft` HTTP endpoints. |
| `DraftStateView`, `DraftPickView`, `StartDraftRequest`, `RecordPickRequest` | Read models / request bodies. |
| **`agent`** | |
| `DraftAgentService` | The manual loop: `ChatModel` + `ToolCallingManager`, loud iteration cap, per-round-trip instrumentation, memory persistence of user turn + final advice only. |
| `DraftAgentTools` | Per-request POJO, six `@Tool` methods, session/rules constructor-bound (schema-unreachable). |
| `ProfileScoringService` + `PlayerProfileView` | History + projection scored under the session's resolved rules (never the public endpoint's default format); role/injury/bye/experience blocks; single home of the injury label and the pair-gated role degradation. |
| `AgentPromptBuilder` | Externalized template (`prompts/draft-agent-system.txt`) + resolved-league summary + `DraftTactics`, printed from session snapshots. |
| `AgentConfig`, `AgentProperties` | Session-keyed `MessageWindowChatMemory`; `readoption.agent.*` (`model`, `max-iterations` 8, `memory-window` 20). |
| `DraftAgentController`, `AdviceRequest/Response`, `AgentLoopLimitException` | `POST /advise`; blank message is a service check (400). |
| **`team`** | |
| `NflTeam`, `TeamSchedule(+Id)` | Reference/landing rows (Sleeper abbrev canonical). |
| `EspnScheduleClient` | ESPN site-API client (its own host, no `X-Fantasy-Filter`). |
| `TeamScheduleSyncService` / `TeamScheduleWriter` | READ (HTTP, no txn) → WRITE (one short txn per team); type-2 filter, crosswalk at the write boundary, loud bye derivation. |
| `TeamContextService` | Single home of the team degradation vocabulary; all player→team context reads; LEFT-JOIN posture; `POSITION_LADDERS` (`WR → {LWR,RWR,SWR}`) as a one-way read boundary; `TeamRoomView`. |
| **`news`** | |
| `PlayerNews(+Id)` | Verbatim landing entity, triple PK. |
| `EspnNewsClient`, `PlayerNewsMapper` | Fetch + typed mapping (Rotowire items only). |
| `PlayerNewsSyncService` / `PlayerNewsWriter` | Per-player fetch loop (one failure never aborts the run); insert-only dedup on the association triple, one txn per player. |
| `NewsEmbeddingService` | Anti-join build, deterministic UUIDs, 500-doc chunks with bounded backoff, HTML-stripped `headline + story` content, generation-tagged metadata. |
| `PlayerNewsSearchService`, `NewsVocabulary`, `PlayerNewsView` | Similarity search hard-filtered to one player and the current model generation; loud degradation strings. |
| `NewsController`, `NewsProperties` | The two ingestion POSTs; `readoption.news.*` (`embedding-model-tag`, `top-k` 5). |
| **`config`** | |
| `VectorStoreConfig` | The explicit `PgVectorStore` bean (see §5). |

## 7. Configuration added in Phase 4

```properties
# The agent
readoption.agent.model=claude-sonnet-4-6
readoption.agent.max-iterations=8
readoption.agent.memory-window=20

# Provider pinning (MANDATORY on Spring AI 2.0 — both chat autoconfigs are
# matchIfMissing=true; without these pins the context builds TWO ChatModels)
spring.ai.model.chat=anthropic
spring.ai.model.embedding=openai

# Anthropic defaults that seed chatModel.getOptions() (2.0 flattened the
# former ...chat.options.* prefix); the agent overrides model per request
spring.ai.anthropic.chat.model=claude-haiku-4-5
spring.ai.anthropic.chat.max-tokens=2000
spring.ai.anthropic.chat.temperature=0.3

# News RAG
spring.ai.openai.embedding.model=text-embedding-3-small
readoption.news.embedding-model-tag=text-embedding-3-small   # keys the derived rows; swap = re-embed + flip
readoption.news.top-k=5
```

Environment: `ANTHROPIC_API_KEY` (advise, reconcile, league parse) and
`SPRING_AI_OPENAI_API_KEY` — since 4.4 the app **does not boot** without the
OpenAI key (the embedding model bean is constructed eagerly).

## 8. How to exercise it

Prerequisites: Postgres up (`docker compose up -d`, host port **5433**), both
API keys set, app on `http://localhost:8080`, and the Phase 1–3 data loaded
(players → stats → projections → reconcile; see README). Examples are bash
`curl`; on PowerShell use `curl.exe` or `--data @body.json`.

### 8a. One-time context loads (4.3 / 4.4)

```bash
# NFL schedule → team_schedule + derived byes (32/32 teams, 17 weeks each)
curl -X POST "http://localhost:8080/api/teams/schedule/sync"

# News corpus → player_news (sequential ESPN fetches, several minutes)
curl -X POST http://localhost:8080/api/news/sync
# → { "playersSynced": 1867, "itemsInserted": 29980, "itemsSkippedExisting": 180, "failed": [...] }

# Embeddings → news_embedding (OpenAI, ~$0.05–0.07 full corpus; idempotent)
curl -X POST http://localhost:8080/api/news/embed
# → { "candidates": 29980, "embedded": 29980, "alreadyCurrent": 0 }
# re-run immediately: { "candidates": 29980, "embedded": 0, "alreadyCurrent": 29980 }
```

(The corpus numbers are the verified 2026-07-11 seed run. News cadence through
camp: ~2×/week manually — the source retains as few as 4–5 blurbs per star
player, and rolled-off items are gone forever.)

### 8b. Start a draft against a confirmed league

```bash
curl -s -X POST http://localhost:8080/api/draft/sessions \
  -H 'Content-Type: application/json' \
  -d '{ "leagueConfigId": 1, "userSlot": 3 }'
```

`201` with the session — note the snapshots the request never contained:

```json
{ "id": 7, "leagueConfigId": 1, "season": 2026, "teamCount": 12,
  "userSlot": 3, "totalRounds": 14, "status": "ACTIVE",
  "createdAt": "2026-07-20T12:00:00", "updatedAt": "2026-07-20T12:00:00" }
```

### 8c. Record picks — yours and everyone else's

```bash
curl -s -X POST http://localhost:8080/api/draft/sessions/7/picks \
  -H 'Content-Type: application/json' \
  -d '{ "playerId": "4866" }'
```

`201` — the server assigned the number, the response derives the rest:

```json
{ "sessionId": 7, "overallPickNo": 1, "round": 1, "teamSlot": 1,
  "playerId": "4866", "pickedAt": "2026-07-20T12:01:00" }
```

Record the same player again and you get the friendly `409`:

```json
{ "type": "https://readoption.app/problems/player-already-drafted",
  "title": "Player Already Drafted", "status": 409,
  "detail": "Player 4866 was already drafted in session 7",
  "playerId": "4866", "overallPickNo": 1 }
```

### 8d. State and board

```bash
curl -s http://localhost:8080/api/draft/sessions/7/state
```

```json
{ "sessionId": 7, "status": "ACTIVE",
  "currentOverallPick": 4, "currentTeamSlot": 4, "onTheClock": false,
  "picksUntilUserNextTurn": 18,
  "userRoster": [ { "playerId": "9488", "name": "Jaxon Smith-Njigba",
                    "position": "WR", "round": 1, "byeWeek": "8" } ],
  "unfilledSlots": { "QB": 1, "RB": 2, "WR": 1, "TE": 1, "FLEX": 1, "BENCH": 6 },
  "gapTeams": [ { "teamSlot": 4, "picksInGap": 2, "positionalCounts": { "RB": 1 } } ] }
```

```bash
curl -s "http://localhost:8080/api/draft/sessions/7/board?position=RB&limit=3"
```

```json
{ "season": 2026,
  "replacementLevels": { "QB": 285.10, "RB": 152.30, "WR": 148.75, "TE": 88.20 },
  "rows": [
    { "playerId": "4866", "name": "Saquon Barkley", "position": "RB",
      "projectedPoints": 226.00, "vorp": 73.70, "adp": 2.10 },
    ...
  ] }
```

(Values are shape-illustrative; every number in a real response traces to the
mart and the league's resolved rules. On the regression anchors: Barkley's
`player_id=4866` scores 208.50 / 226.00 / 243.50 Standard / Half-PPR / PPR.)

### 8e. Ask the agent

```bash
curl -s -X POST http://localhost:8080/api/draft/sessions/7/advise \
  -H 'Content-Type: application/json' \
  -d '{ "message": "Which player should I pick?" }'
```

`200` with the advice and the loop's own accounting:

```json
{ "advice": "You're on the clock at pick 4. The board's best value is ...",
  "iterations": 1, "totalTokens": 6420, "latencyMs": 12599 }
```

What that one request looks like from the inside (real instrumentation, the
post-4.4 six-tool baseline):

```
DEBUG DraftAgentService : iter 0 | hasToolCalls=true | tools=[getDraftState{}, getDraftBoard{}] | in=2430 out=72 | cumulative_in=2430 | 2076 ms
DEBUG DraftAgentTools   : tool exec -> getDraftState [session 5]
DEBUG DraftAgentTools   : tool exec <- getDraftState [session 5] pick 1 | 5 ms
DEBUG DraftAgentTools   : tool exec -> getDraftBoard [session 5] position=null limit=null
DEBUG DraftAgentTools   : tool exec <- getDraftBoard [session 5] 20 rows | 36 ms
DEBUG DraftAgentService : iter 1 | hasToolCalls=false | tools=[] | in=3532 out=386 | cumulative_in=5962 | 10478 ms
INFO  DraftAgentService : Draft advice session 5: 1 tool iterations, 6420 total tokens, 12599 ms
```

News-grounded questions route through the sixth tool:

```bash
curl -s -X POST http://localhost:8080/api/draft/sessions/7/advise \
  -H 'Content-Type: application/json' \
  -d '{ "message": "Any injury news on Mahomes? Why did he fall on draft boards?" }'
```

The model supplies `playerId` (via `findPlayer` or draft state) and a free-text
query; `topK` and the session are server-side. Citations arrive dated ("per a
March 2 report"), and a player with no corpus gets an honest "I have no news
for this player," never speculation.

### 8f. Verify empirically

```bash
psql -h localhost -p 5433 -U <user> readoption -c "
  SELECT s.id, s.status, s.team_count, s.total_rounds, count(p.player_id) AS picks
  FROM draft_session s LEFT JOIN draft_pick p ON p.session_id = s.id
  GROUP BY s.id ORDER BY s.id;"

psql -h localhost -p 5433 -U <user> readoption -c "
  SELECT count(*) AS items, count(DISTINCT player_id) AS players,
         min(published) AS oldest, max(published) AS newest
  FROM player_news;"

psql -h localhost -p 5433 -U <user> readoption -c "
  SELECT metadata->>'embedding_model' AS generation, count(*)
  FROM news_embedding GROUP BY 1;"
```

## 9. The 4.M migration (mid-phase, deliberate)

Between 4.3.1 and 4.4 the platform moved: **Java 17→21, Spring Boot
3.5.14→4.0.7, Spring AI 1.1.8→2.0.0 (official-SDK-backed Anthropic), Jackson 3
(`tools.jackson`), Testcontainers 2.0.5** — because 4.4 wanted Spring AI 2.0's
vector-store stack, and migrating *before* building on it beats migrating under
it. The findings ledger is `docs/specs/phase-4M-findings.md`. The one that
matters most: **F-12** — Spring AI 2.0's Anthropic module discards
non-`AnthropicChatOptions` prompt options wholesale, so the agent's bare
`ChatModel.call` path silently sent *no tools on the wire* (symptom:
iterations=0, hallucinated tool-call XML in advice) while every in-suite test
stayed green. The fix derives full provider-typed options via
`((AnthropicChatOptions) chatModel.getOptions()).mutate()…build()`, and the
lesson is generic: mocks can't catch this class of bug — verify at the wire
(`ANTHROPIC_LOG=debug`, grep the outbound body for `"tools"`).

## 10. Testing

The four slice patterns carried Phase 4; 347 tests green at close (253 after
4.2 → 287 after 4.3 → 310 after 4.3.1/4.M → 340 after 4.4 → 347 after 4.4.2).
Phase-4-specific highlights:

- **The schema-safety test** parses the generated JSON schema of all six tools
  and asserts each exposes exactly its documented parameters — `sessionId` and
  `scoringRules` unreachable by construction, re-proven on every build.
- **Two-sources fixtures disagree on purpose** (session snapshot 14 teams vs
  config-derived 12) so re-derivation can never pass by coincidence; the 4.4.2
  season-interpolation pin asserts at 2027, the year a hardcoded implementation
  would get wrong.
- **The agent loop is tested with a stubbed `ChatModel`** (scripted tool-call
  responses) — never a live model; the cap test proves a failed loop persists
  nothing to memory.
- **Real-Postgres slices** (`pgvector/pgvector:pg16` via Testcontainers) pin
  upsert idempotency, the pick unique-constraint 409 path (including that the
  dialect populates the constraint name the match relies on), the news
  triple-PK dedup, and the vector retrieval slice (fake 1536-dim embedding
  model, real pgvector: player filter isolates one corpus, old-generation
  vectors never surface).
- **Regression anchors intact throughout**: Barkley 208.50 / 226.00 / 243.50,
  Mahomes −2/INT, the profile anchors tracing to the Rotowire raw line.

## 11. Drift from spec

Deliberate divergences between the built code and the phase specs, all flagged
in review at the time:

1. **`getPlayerProfile` takes `String playerId`** — `Player.id` is a String;
   the 4.2 spec said `long`.
2. **`getDraftBoard` returns `DraftBoardView`**, not the spec pseudocode's
   `List<PlayerValue>` — the view carries name/VORP/ADP, which the spec's own
   tool description promises.
3. **`StartDraftRequest` carries no `teamCount`** (owner refactor): snapshots
   are frozen from the config, closing the dual-source flaw the review round
   found.
4. **4.3.1 wire names are camelCase**; session-drafted flags are computed at
   the tool layer (the `team` package never points at the draft domain — the
   dependency arrow stays clean); room entries carry `playerId`.
5. **4.4.2 shipped zero migrations** — the spec planned a `draft_year` column;
   the source audit found `years_exp` already landed with 0/409 missing, and
   the probe set falsified the assumed semantics (accrued seasons, frozen for
   out-of-league players), which reshaped the derivation contract instead.
6. **`PlayerNewsRepository.findExistingIds` returns `PlayerNewsId` triples**
   (4.4.1 accepted deviation).
7. **The 4.3.1 addendum's cross-field round check was not implemented** — the
   flat 1..30 bound stands, keeping the tactics validator decoupled from the
   engine-bound roster spec.

## 12. Not in Phase 4

- **Phase 4.5 (strategy layer / stacking graduation) was not built** — folded
  forward; stacking stays in `DraftTactics.freeformNotes`, which the agent
  already reads.
- Deferred by 4.4 spec §11: FantasyPros news backfill, Story-item chunking,
  Matryoshka embedding dimensions, old-generation vector cleanup, sync
  automation.
- The architectural experience lever (experience on board rows / a board
  filter parameter) — ledgered, graduation-gated.
- Multi-writer draft concurrency (two simultaneous `recordPick`s on the same
  session can collide on the PK as a 500 — acceptable single-writer).
- The frontend — begins after Phase 4; every surface above is exercised by
  curl/tests only.
