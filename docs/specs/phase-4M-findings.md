# Phase 4.M — Findings Ledger

Executed 2026-07-09. Boot 3.5.14 → **4.0.7**, Spring AI 1.1.8 → **2.0.0**, Java 21
(unchanged, Commit 1). Suite: **310/310 green** (`mvn clean verify`, Testcontainers on
`pgvector/pgvector:pg16`). Scoring anchors (Barkley 208.50/226.00/243.50, Mahomes −2/INT)
asserted in-suite — R-1/R-2 satisfied by the green run.

## V-items (spec §2)

- [x] **V-1 CLOSED** — Anthropic starter artifactId unchanged
  (`spring-ai-starter-model-anthropic`). Module now backed by the official
  `com.anthropic:anthropic-java` SDK; `AnthropicApi` removed (we never used it);
  `AnthropicChatOptions` survives with builder. **Behavioral flag:** Anthropic
  `maxTokens` default changed 500 → 4096 in 2.0 — neutralized here because we set
  `spring.ai.anthropic.chat.max-tokens=2000` explicitly.
- [x] **V-2 CLOSED (amended)** — Boot 4 still manages Flyway (11.x) versions, but the
  auto-configuration moved out of `spring-boot-autoconfigure` into the
  `spring-boot-flyway` module. `flyway-core` replaced by `spring-boot-starter-flyway`
  (flyway-core rides transitively, still Boot-managed, no version pins);
  `flyway-database-postgresql` kept as-is. Evidence: startup log
  `Successfully validated 14 migrations`, `Schema "public" is up to date`.
- [x] **V-3 CLOSED** — `commons-csv` 1.12.0 untouched.
- [x] **V-4 CLOSED** — `spring-boot-properties-migrator` added, reported exactly three
  renames, all Spring AI: `spring.ai.anthropic.chat.options.{model,max-tokens,temperature}`
  → `spring.ai.anthropic.chat.{model,max-tokens,temperature}`. Fixed in
  `application.properties`; second boot shows **zero** migrator warnings; migrator
  removed before commit.

## Forced changes (one finding per change)

- [x] **F-1** pom: parent 3.5.14 → 4.0.7; `spring-ai.version` 1.1.8 → 2.0.0.
- [x] **F-2** pom: `spring-boot-starter-web` → `spring-boot-starter-webmvc`
  (classic starter deprecated in Boot 4; migration guide directs the rename).
- [x] **F-3** pom: added `spring-boot-starter-restclient` — Boot 4 modularized the HTTP
  client; `RestClient.Builder` is no longer auto-configured by the web starter.
  Evidence: `contextLoads` failed with UnsatisfiedDependency on `espnScheduleClient`
  constructor param 0 until added.
- [x] **F-4** pom + tests: test slices modularized — added
  `spring-boot-starter-webmvc-test` and `spring-boot-starter-data-jpa-test`; rewrote
  imports in ~20 test files: `WebMvcTest` → `org.springframework.boot.webmvc.test.autoconfigure`,
  `DataJpaTest` → `org.springframework.boot.data.jpa.test.autoconfigure`,
  `TestEntityManager` → `org.springframework.boot.jpa.test.autoconfigure`,
  `AutoConfigureTestDatabase` → `org.springframework.boot.jdbc.test.autoconfigure`.
  Annotations themselves unchanged; slice behavior identical (suite green).
- [x] **F-5** pom: Boot 4 dropped Testcontainers dependency management → imported
  `testcontainers-bom` **2.0.5** (the version `spring-boot-testcontainers` 4.0.x is
  built against); TC 2.x renamed modules: `junit-jupiter` → `testcontainers-junit-jupiter`,
  `postgresql` → `testcontainers-postgresql`. No source changes needed —
  `@ServiceConnection` + `PostgreSQLContainer` imports survived; singleton base
  container pattern runs green on the real pgvector image.
- [x] **F-6** Jackson 3 package sweep (§3a): `com.fasterxml.jackson.{core,databind}` →
  `tools.jackson.{core,databind}` in `SleeperClient`, `RotowireProjectionMapper`,
  `EspnProjectionMapper`, `LeagueParsingService` + 8 test classes. Annotation imports
  (`com.fasterxml.jackson.annotation.*`) untouched per spec §3a.
  `JsonProcessingException` (checked, IOException) → `JacksonException` (unchecked):
  catch clauses adapted 1:1; the two mappers keep the null-payload-on-failure posture,
  `LeagueParsingService.toJson` keeps IllegalStateException. **Minor accepted behavior
  change:** SleeperClient parse failures now wrap via their own catch and no longer set
  the interrupt flag (previously they rode the `IOException|InterruptedException` catch,
  which interrupted the thread on a parse error — a latent wart, not preserved).
- [x] **F-7** Jackson 3 immutable mappers: `new ObjectMapper().configure(...)` →
  `JsonMapper.builder().disable(FAIL_ON_UNKNOWN_PROPERTIES).build()` in `SleeperClient`
  and `PlayerSyncServiceTest` (kept explicit even though it is the Jackson 3 default,
  so the lenient posture stays visible). Bare `new ObjectMapper()` in tests →
  `new JsonMapper()`. `@WebMvcTest` slices keep injecting by `ObjectMapper` supertype —
  Boot 4's auto-configured `JsonMapper` satisfies it.
- [x] **F-8** Spring AI 2.0 agent loop (§4a): removed
  `.internalToolExecutionEnabled(false)` (no replacement; external execution is the only
  mode). Loop structure, iteration cap, DEBUG instrumentation byte-identical in intent.
  `ToolCallingManager.executeToolCalls(Prompt, ChatResponse)` and
  `ToolExecutionResult.conversationHistory()` signatures survived 2.0 unchanged.
  Verified `ToolCallingManager` **bean is still auto-configured**
  (`ToolCallingAutoConfiguration` in `spring-ai-autoconfigure-model-tool:2.0.0`) — the
  constructor injection in `DraftAgentService` still resolves at startup.
- [x] **F-9** Spring AI 2.0 options (§4b): `ChatClient…options(...)` now takes the
  options **builder** (the built-`ChatOptions` overload is gone) — adapted the two call
  sites (`VerdictClassifier`, `LeagueParsingService`), dropping only the `.build()`.
  No setter-style options existed anywhere (already builder-based).
- [x] **F-10** §4c schema gate: the schema-parsing safety test passes with a single
  test-*helper* edit — Jackson 3 renamed `JsonNode.fieldNames()` →
  `propertyNames()` (returns `Collection<String>`). The tool schema surface itself
  (five tools, documented params only, no sessionId/rules leakage) survived unmodified.
- [x] **F-11** `application.properties`: the three V-4 property renames.

## §4d / §4e / §5 verification (no code change needed)

- [x] **4d ChatMemory** — `MessageWindowChatMemory` builder + direct `get/add` with
  explicit conversationId unchanged in 2.0 (the conversationId breaking changes hit
  advisors only, which we don't use). Memory-window semantics tests green.
- [x] **4e BeanOutputConverter** — survives in 2.0; kept, zero change; type boundary
  (Verdict / ParsedLeague — model never widens what it can write) intact. Note: 2.0
  regenerated JSON-schema output (OpenAPI `format` hints, `@JsonProperty(required=false)`
  handling) — the schema *text* sent to the model may differ; parse seams are covered by
  tests, live behavior lands in R-6.
- [x] **§5 Hibernate 7 residue** — zero red tests; **no persistence code touched**
  (Persistable/isNew, `@IdClass`, bulk JPQL flush ordering, constraint-name extraction
  all pinned by the integration suite and green as-is).
- [x] **§5 @MockBean stragglers** — none (grep clean; `@MockitoBean` everywhere).

## R-3 / R-4 evidence (pre-verified; Sorin re-runs the runbook)

- [x] **R-3** — app boots against the existing DB on 4.0.7/2.0.0; Flyway validates 14
  migrations, no new entries; `ddl-auto=validate` passes; zero migrator warnings after
  the property fix.
- [x] **R-4** — four endpoints re-curled and lexeme-diffed (numbers compared as raw
  lexemes, formatting-insensitive):
  - state: **identical**. 404 ProblemDetail: **identical**.
  - profile (`?format=STANDARD_4PT` — the baseline was captured with the explicit
    param; the endpoint defaults to 6PT): **identical incl. `416.40` scale**.
  - board: all values identical; the new output carries NUMERIC(6,2) scale (`6.90`)
    where the baseline file shows `6.9`. **Proven a baseline capture artifact, not a
    Jackson 3 change**: the baseline board file contains zero trailing-zero lexemes
    across ~25 decimals while the same-session profile baseline preserves `416.40`,
    and the DB stores `6.70`/`4.90` (scale 2) — Jackson 2 also emitted scale 2 at
    runtime; the board capture went through a zero-stripping formatter (jq-style).
    When re-running R-4, diff with a scale-preserving comparer.

## Open (owner's runbook)

- [ ] **R-5** — source_payload sync diff (rotowire 10210, espn 10213) after re-running
  the two sync jobs. Note: payloads now serialize through Jackson 3 (`JsonMapper` bean);
  Jackson 3 default changes are value-shape-visible here if anywhere.
- [ ] **R-6** — agent behavioral equivalence, frozen session 4, two runs (tool set,
  iterations ≤ 3, tokens 9,000–17,000, numbers trace to the board endpoint).

## DEFERRED (not in this diff)

- ToolCallingAdvisor / agent-loop redesign — design session first (spec §4a).
- `spring.jackson.use-jackson2-defaults` — **not** enabled; we accept Jackson 3
  defaults, and the deterministic surface is proven unchanged (R-4). Revisit only if
  R-5 shows unacceptable payload drift.
- New 2.0 Anthropic options (service tier, thinking display, web-search tool) — not adopted.
- SleeperClient → RestClient standardization — unchanged deferral.
- pgvector starter / VectorStore (Phase 4.4): reminder — 2.0 pgvector starter needs an
  explicit `spring-boot-starter-jdbc`.
