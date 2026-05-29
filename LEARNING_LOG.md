# AI Development Learning Log

A running log of what I learn, build, and get confused about while learning AI development with my Java/Spring background.

**Goal:** Career evolution into AI + Java backend roles. Build a fantasy football draft assistant as the portfolio project.

**Stack focus:** Java 17, Spring Boot, Spring AI, Anthropic Claude API, PostgreSQL/pgvector, Maven, IntelliJ, Claude Code.

---

## Phase 0 — Foundations

### Week 1 — First API Calls

#### Day 1 — Conceptual Foundation

**What I did**
- Set up Anthropic API account at console.anthropic.com
- Got an API key, added credits
- Read intro docs and models overview

**What I learned**
- An LLM API call is just an HTTP POST. JSON in, JSON out. Not magic.
- There are multiple Claude models with different price/speed/intelligence tradeoffs:
  - Haiku — cheapest, fastest, good for learning and high-volume simple tasks
  - Sonnet — balanced, good for production
  - Opus — most capable, most expensive
- I pay per token (input + output). Cost engineering is part of the job.
- The role of an "AI Developer" is mostly a backend engineer who integrates LLMs into apps. Not someone who builds models from scratch (that's ML Engineer / Researcher — different career path).

**Key mental model**
> LLM = REST API on someone else's servers. Everything else (Spring AI, agents, MCP) is abstraction over this.

---

#### Day 2 — First API Calls With curl

**What I did**
- Made my first call to the Anthropic API using curl
- Experimented with `max_tokens`, different prompts, system prompts
- Watched what happens when generation gets cut off

**What I learned**

**Anatomy of an API call**
- Endpoint: `https://api.anthropic.com/v1/messages`
- Required headers:
  - `x-api-key: YOUR_KEY`
  - `anthropic-version: 2023-06-01`
  - `content-type: application/json`
- Body fields:
  - `model` — which Claude version to call
  - `max_tokens` — hard limit on response length
  - `system` — instructions defining Claude's role/behavior (optional)
  - `messages` — array of `{role, content}` objects

**Anatomy of a response**
- `content[].text` — the actual answer
- `usage.input_tokens` / `usage.output_tokens` — what you'll be billed for
- `stop_reason` — why generation stopped:
  - `end_turn` — Claude finished naturally (good)
  - `max_tokens` — hit the limit, response was cut off (need to raise limit)
  - others exist for tool use, stop sequences, etc.

**Key insight from a mistake**
- Set `max_tokens: 50` on a question asking for 50 NFL players
- Got `stop_reason: max_tokens` and a cut-off response
- This wasn't a failure — the API did exactly what I told it to
- Lesson: token limits are a real production concern. Set them deliberately.

**System prompt vs user message**
- **User message** = the task ("Give me the top 50 players")
- **System prompt** = how Claude should behave for the whole conversation (role, tone, rules)
- Set once at the request level, not per message
- Same question + different system prompts = very different outputs
- For my app: I'll hardcode good system prompts in Java; users won't write them

**Why I don't give Claude raw data sources**
- Claude doesn't fetch data. It reasons over data **I give it**.
- The flow for my fantasy app:
  1. My Java app downloads data from ESPN/Sleeper into PostgreSQL (ETL — pure backend)
  2. User asks a question
  3. My app queries Postgres for relevant data
  4. My app sends that data + the question to Claude
  5. Claude reasons and replies
  6. My app returns the answer
- Reasons: speed, cost, correctness (avoids hallucination), control over what Claude sees

---

#### Day 3 — First Java Call (HTTP Client + Jackson)

**What I did**
- Set up Maven project: `com.sorin.fantasy:claude-playground`
- Added Jackson dependency (`jackson-databind`)
- API key stored as environment variable in IntelliJ run config (never hardcoded)
- Built `ClaudeClient` class with `ask(String question)` method
- Created custom `ClaudeApiException` for error handling
- Multiple iterations of code review and refactoring

**Initial mistakes I made (and lessons)**

1. **Hand-crafted JSON with string concatenation**
   - Got `invalid_request_error` because the question wasn't quoted in the JSON
   - **Lesson:** Don't hand-craft JSON. Use a library (Jackson). The moment you find yourself escaping quotes manually, you're solving a problem that's already been solved.

2. **Created `HttpClient` inside the method**
   - Wasteful — allocates new connection pool every call
   - **Fix:** Initialize once in constructor, reuse via field

3. **Threw raw checked exceptions to the caller**
   - `URISyntaxException`, `IOException`, `InterruptedException` polluting method signatures
   - **Fix:** Wrap in domain-specific `ClaudeApiException` (RuntimeException)

**Patterns I used (interview-worthy)**

1. **Exception translation**
   > Translate low-level exceptions (IOException, JsonProcessingException, InterruptedException) into a domain-specific exception (ClaudeApiException) at the API boundary. Callers don't need to know the implementation uses HTTP and JSON. Tradeoff: lose specific exception types, gain abstraction.

2. **Constructor-injected dependencies + immutability**
   > Initialize all collaborators (HttpClient, ObjectMapper) and configuration (apiKey) in the constructor. Mark fields `final`. Result: thread-safe, immutable client. Spring takes this pattern and automates it via DI in the next phase.

3. **Fail fast on misconfiguration**
   > Validate API key in constructor. If missing, throw immediately at startup — not on the first user request hours later.

4. **`Thread.currentThread().interrupt()` after catching `InterruptedException`**
   > Catching `InterruptedException` clears the interrupt flag. If you don't restore it, code higher up the call stack can't tell the thread was interrupted. This is a real Java interview question.

5. **`URI.create()` over `new URI()`**
   > For hardcoded, known-valid URLs, prefer `URI.create()` — it throws unchecked `IllegalArgumentException` instead of checked `URISyntaxException`. Less boilerplate, fewer noise exceptions.

**HTTP client mechanics**
- `HttpClient.newHttpClient()` / `HttpClient.newBuilder()` — builds the recipe
- `HttpRequest.newBuilder().POST(...)` — builds the request
- `client.send(request, BodyHandler)` — **THIS is where the network call actually happens**
- `BodyHandler` tells the client how to convert response bytes:
  - `ofString()` — body as String (most common)
  - `ofFile(Path)` — write directly to file
  - `ofByteArray()` — raw bytes
  - `discarding()` — ignore body
  - `ofLines()` — stream line by line
- Same client can use different handlers per call

**Final code structure**
- `ClaudeClient` — public `ask()`, private `sendRequest()`, `createRequest()`, `getBody()`, `handleResponse()`
- Each method has one job
- `ClaudeApiException` — custom unchecked exception
- `EnvTest` — simple `main` to drive it

---

#### Day 4 — Breaking Things On Purpose

**What I did**
Ran 6 intentional failure experiments. Predicted behavior first, then observed.

**Experiments and findings**

| # | Scenario | Predicted | Actual | Where it surfaced |
|---|----------|-----------|--------|-------------------|
| 1 | Invalid API key | 401 | 401 | `handleResponse` → `ClaudeApiException` |
| 2 | `max_tokens: 5` | Truncated response, no error | Top 50 list returned but cut off, `stop_reason: max_tokens` | No exception — **200 OK with bad content** |
| 3 | Fake model name | 4xx error | 404 not found | `handleResponse` → `ClaudeApiException` |
| 4 | No network | IOException | IOException wrapped in `ClaudeApiException` | `sendRequest` catch block |
| 5 | Empty question `""` | API would reject | 400 invalid_request_error | `handleResponse` |
| 6 | Massive question (370k chars) | Might be rejected | Succeeded with high cost | No issue — but **expensive** |

**Key insights**

1. **HTTP 200 ≠ logical success.** Experiment 2 was the most important.
   - The API call succeeded (200)
   - My code happily returned the truncated content
   - The user's question was NOT answered properly
   - For real LLM apps: must check `stop_reason` and validate content, not just status code

2. **The API trusts the client completely (the context bomb problem)**
   - Experiment 6: 370,000 char input → accepted, billed, processed
   - No "are you sure?" check, no client-side limit
   - In production, this is how AI apps go bankrupt
   - **Banking parallel:** like transaction size limits — must validate input on your side before sending

3. **Two different timeouts exist, I only have one**
   - `HttpClient.connectTimeout` = time to establish TCP connection
   - `HttpRequest.timeout` = time waiting for the full response (I'm missing this)
   - Without the second, a slow Claude response could hang forever

4. **Client-side validation is missing**
   - Empty input made it all the way to the API before being rejected
   - Should be caught in `ask()` before even building the request
   - Faster failure, no wasted API call

**Improvements added after Day 4**
- Input validation in `ask()` — reject null/blank questions with `IllegalArgumentException`

**Patterns learned (interview-worthy)**

6. **HTTP success vs logical success in LLM APIs**
   > Unlike normal REST APIs, a 200 from an LLM doesn't mean the request was useful. Must validate `stop_reason == "end_turn"` and content is non-empty. Truncated responses are a silent failure mode.

7. **Defensive input validation at the API boundary**
   > Never trust input size to be reasonable. LLM APIs charge per token and have no size guardrails. Client must enforce a max input size before sending. Without this, a malicious or careless caller can run up massive bills.

**Things to handle properly later (in Spring AI / Week 2)**
- Use SLF4J/Logback instead of `System.out.println` for token usage logging
- Add `HttpRequest.timeout` (in addition to `connectTimeout`)
- Add retry logic with exponential backoff (Claude API returns 529 when overloaded)
- Log full request/response for debugging
- Consider making `ObjectMapper` static final (thread-safe, expensive to create)
- Token usage should be returned alongside the response, not just printed
- Validate `stop_reason` after every call — warn if `max_tokens`
- Token-count input before sending (use Anthropic's token counting endpoint or a tokenizer library)
- Set a max input token limit, reject before sending

---

## Project Setup Notes

**Folder structure (final)**
```
D:\AI\ai-learning\
└── claude-playground\          ← own git repo
    ├── .git\
    ├── .gitignore
    ├── .idea\
    ├── LEARNING_LOG.md
    ├── pom.xml
    └── src\
        └── main\java\com\sorin\fantasy\
            ├── ClaudeApiException.java
            ├── ClaudeClient.java
            └── EnvTest.java
```

**Git strategy:** one repo per project (not one repo for all of `ai-learning`).
Reasons: each project becomes its own GitHub repo, can be made public/private independently, cleaner portfolio.

**API key handling**
- Stored as environment variable in IntelliJ run config (per-run, not system-wide)
- Validated in `ClaudeClient` constructor (fail fast)
- `.env`, `*.env`, and IDE folders are in `.gitignore`
- Never printed in full — only first 10 chars for verification

---

## Vocabulary I've Learned

### Core Concepts

| Term | Definition |
|------|------------|
| **LLM** | Large Language Model. Trained AI model: text in, text out. Accessed via API. |
| **Prompt** | The text I send to the LLM. |
| **Token** | Subword unit LLMs process. ~1 token ≈ 4 chars in English. Billed input + output. Common words = fewer tokens, rare words = more. |
| **Context** | Everything the LLM can see in a single call: system prompt, conversation history, injected data, current message. |
| **Context window** | Max tokens the LLM can process at once. Claude ~200k. Think of it as the desk size — anything that doesn't fit falls off. |
| **Hallucination** | When the LLM confidently states something false. The #1 production problem. Mitigations: RAG, tool calling, grounding, honesty instructions. |
| **System prompt** | Persistent instructions defining the LLM's role/behavior. Changes reasoning patterns, not just tone. |
| **User message** | The actual task/question sent in the `messages` array. |
| **Temperature** | Controls randomness. 0 = deterministic (data tasks). 0.3–0.5 = focused analysis. 0.7+ = creative. For recommendations use low values. |
| **max_tokens** | Hard limit on response length. Cost + safety control. |
| **stop_reason** | Why generation stopped: `end_turn` (good), `max_tokens` (truncated), others for tool use. |
| **Context bomb** | When a caller sends huge input that succeeds, gets billed, and racks up cost. Client must validate input size. |
| **Streaming** | Tokens sent as generated instead of waiting for full response. Improves perceived latency. Spring AI supports via `Flux<String>`. |

### Memory

| Term | Definition |
|------|------------|
| **LLM statelessness** | Each API call is independent. The LLM has zero memory between calls. Conversation continuity is simulated by the application. |
| **Conversation memory (short-term)** | Simulated by sending full message history with every request. Spring AI's `ChatMemory` manages this automatically. |
| **Persistent memory (long-term)** | Facts about users stored in a database, loaded into the prompt per request. The LLM reads it but doesn't own it. |

### Retrieval & Data Grounding

| Term | Definition |
|------|------------|
| **RAG (Retrieval-Augmented Generation)** | Retrieve relevant data → inject into prompt → LLM reasons over it. Two flavors: SQL-based (structured data) and vector-based (unstructured). |
| **Grounding** | Basing LLM responses on provided facts rather than training data. RAG and tool calling are grounding techniques. |
| **Embedding** | Text → vector of numbers capturing meaning. Similar text → similar vectors. Used for semantic search. Obtained via API call. |
| **Vector database** | Stores embeddings, finds similar ones via nearest-neighbor search. e.g., pgvector, Pinecone, Weaviate. |
| **Chunking** | Splitting long documents into smaller pieces (200–500 tokens) before embedding. Too small loses context, too large dilutes meaning. |

### Tool Use & Agents

| Term | Definition |
|------|------------|
| **Tool calling / Function calling** | LLM requests function executions; my code runs them; results go back to the LLM. The LLM never directly accesses external systems. |
| **Agent** | LLM in a reasoning loop: receive goal → plan → use tools → observe results → decide if done → repeat. Has autonomy over which tools to use and in what order. |
| **MCP (Model Context Protocol)** | Standard protocol for exposing tools to LLMs. Like JDBC for AI tools — define once, any MCP-compatible LLM can use them. |
| **Guardrails** | Constraints in the system prompt preventing unwanted behavior. e.g., "never recommend more than 3 players from the same team." |

### Prompt Engineering

| Term | Definition |
|------|------------|
| **Zero-shot** | Asking the LLM to do a task without examples. Works for flexible, open-ended questions. |
| **Few-shot** | Providing examples before the task. Dramatically more reliable for consistent, structured output. |
| **Chain of thought (CoT)** | Asking the LLM to reason step-by-step before answering. Intermediate steps improve final answer quality. |

### Java / Spring Concepts From This Project

| Term | Definition |
|------|------------|
| **BodyHandler** | Java HttpClient concept. Tells the client how to convert response bytes (String, File, byte[], etc.). |
| **Exception translation** | Pattern: wrap low-level exceptions in domain-specific ones at the API boundary. |
| **ParameterizedTypeReference** | Spring's workaround for Java type erasure. Preserves generic type info at runtime for deserialization. |
| **TypeReference (Jackson)** | Jackson's equivalent of `ParameterizedTypeReference`. Same type erasure problem, same anonymous subclass solution. Used for `List<T>`, `Map<K,V>` deserialization. |
| **@IdClass** | JPA annotation for composite primary keys. References a separate class holding the key fields. Fields must match `@Id` fields by name and type. |
| **Composite primary key** | PK made of multiple columns (e.g., `player_id + year`). Natural key when the combination uniquely identifies a row. Alternative to surrogate keys. |
| **Owning side** | The JPA entity whose table has the FK column. Owns the relationship mapping. Uses `@ManyToOne` + `@JoinColumn`. |
| **Inverse side** | The optional "other end" of a JPA relationship. Uses `mappedBy`. Doesn't add a DB column — just a Java navigation convenience. |
| **FetchType.LAZY** | Load related entity only when accessed. Uses a Hibernate proxy. Always prefer over EAGER for `@ManyToOne`. |
| **FetchType.EAGER** | Load related entity immediately via JOIN. Default for `@ManyToOne`. Almost always wrong — override to LAZY. |
| **N+1 problem** | 1 query loads N entities, then N additional queries load each entity's relationship. Fix: `JOIN FETCH` or `@EntityGraph`. |
| **JOIN FETCH** | JPQL clause that forces a single query with a JOIN to load related entities. Solves N+1 at the query level. |
| **@EntityGraph** | Declarative alternative to `JOIN FETCH`. Annotate repository method to specify which relationships to eagerly load. |
| **Hibernate proxy** | ByteBuddy-generated placeholder for lazy-loaded entities. Triggers real DB query on first access. Causes serialization errors if Jackson hits it. |
| **Persistable** | Spring Data interface. Override `isNew()` to tell JPA whether to INSERT or UPDATE — eliminates SELECT-per-entity with manual IDs. |
| **insertable/updatable = false** | `@JoinColumn` setting. Marks a mapping as read-only when two fields map to the same column. Prevents write conflicts. |
| **Extracted interface** | Refactoring pattern: define an interface from an existing class's public API. The class already satisfies the contract, you just declare it. Used for `StatLine` extracted from `PlayerStats` getters. |
| **Strategy pattern (via enum)** | Each enum value encapsulates a different strategy (scoring rules). The service takes the enum and uses its values — no switch needed. Adding a strategy = adding an enum value. |
| **`BigDecimal` String constructor** | `new BigDecimal("0.04")` parses exact decimal. `new BigDecimal(0.04)` uses the double value which can't represent 0.04 exactly. Always use String constructor for precise decimals. |
| **Physical naming strategy** | Hibernate layer that converts Java field names to database column names. Spring Boot configures `CamelCaseToUnderscoresNamingStrategy`: `fumblesLost` → `fumbles_lost`. |
| **`@DisplayName`** | JUnit 5 annotation. Provides human-readable test names in output instead of method names. Documents what each test verifies. |
| **@PrePersist / @PreUpdate** | JPA lifecycle callbacks. Methods called automatically before INSERT and UPDATE. Standard way to handle audit timestamps — entity owns its timestamp logic. |
| **@Query (JPQL)** | Custom queries beyond Spring Data method naming. Operates on entity classes and field names (not tables/columns). Portable across databases. `nativeQuery = true` for raw SQL. |
| **ResponseEntity** | Spring class for explicit HTTP response control — status code, headers, body. Used for action endpoints where a plain object return isn't informative enough. |
| **Lombok** | Compile-time annotation processor generating boilerplate (getters, setters, builders, constructors). Absent at runtime — marked `<optional>` in Maven. |
| **@Builder (Lombok)** | Generates a builder pattern for a class. Use `@Builder.Default` to preserve field initializers. Requires `@AllArgsConstructor` + `@NoArgsConstructor` for JPA entities. |
| **@Builder.Default** | Tells Lombok's builder to use the field initializer value instead of the type default. Critical for `isNew = true` in Persistable entities. |
| **@Data (Lombok)** | Dangerous on JPA entities. Combines @Getter + @Setter + @ToString + @EqualsAndHashCode + @RequiredArgsConstructor. The generated equals/hashCode on all fields breaks Hibernate. |
| **@EqualsAndHashCode (Lombok)** | Safe on ID/value classes. Dangerous on JPA entities — generates equals/hashCode using all fields, breaking Hibernate Sets and triggering lazy loading. |
| **AOP proxy (Spring)** | Wrapper object Spring creates around beans to add cross-cutting behavior (@Transactional, @Cacheable). External calls go through the proxy; internal `this` calls bypass it. |
| **Self-call proxy bypass** | When a bean method calls another method on the same bean via `this`, it bypasses the AOP proxy. @Transactional on the inner method is ignored. Fix: extract to a separate bean. |
| **Computed/derived table** | Database table populated entirely by application logic (e.g., scoring results). Can be fully recomputed. FKs often unnecessary — application guarantees integrity. |
| **Leftmost prefix rule** | A composite index on (A, B, C) supports queries on A, A+B, or A+B+C. Queries on B or C alone can't use it. Determines whether a separate index is needed. |
| **show-sql vs SLF4J logging** | `show-sql=true` bypasses the logging framework (no timestamps, no levels). Production: use `logging.level.org.hibernate.SQL=DEBUG` for controllable SQL logging. |

### Fantasy Football Domain

| Term | Definition |
|------|------------|
| **Fantasy points** | Calculated score from real NFL stats using a scoring format's rules. The currency of fantasy football. |
| **Scoring format** | Rules for converting stats to fantasy points. Varies by reception value (Standard/Half-PPR/PPR) and passing TD value (4pt/6pt). |
| **PPR (Point Per Reception)** | Scoring format awarding 1 point per catch. Boosts pass-catchers (slot WRs, receiving RBs). |
| **Standard scoring** | Zero points per reception. Favors volume runners and deep-threat WRs over high-volume pass-catchers. |
| **PPG (Points Per Game)** | Fantasy points divided by games played. Fair comparison metric across players with different games played. |
| **Positional scarcity / VORP** | Value Over Replacement Player. How much better a player is than the last starter at their position across all league teams. Drives draft strategy — scarce positions are more valuable. |
| **ADP (Average Draft Position)** | Where a player is typically drafted across many leagues. Gap between actual value and ADP = draft value. |
| **Roster configuration** | Number of starters per position (1QB/2RB/2WR/1TE/1FLEX). Determines which positions are scarce and therefore valuable. |
| **Flex slot** | Roster spot that accepts multiple positions (typically RB or WR). Increases demand for those positions. |

---

## When I'll Use Each Concept

| Concept | Phase |
|---------|-------|
| Structured output | ✅ Done (Week 2 Day 4) |
| System prompts | ✅ Done (Week 2 Day 5) |
| Context management | Phase 2+ (when prompts get large with player data) |
| Conversation memory | Phase 3 (multi-turn draft conversations) |
| RAG (SQL-based) | Phase 2–3 (query player stats, inject into prompt) |
| RAG (vector-based) | Phase 3+ (scouting reports, articles) |
| Embeddings + pgvector | Phase 3 |
| Few-shot prompting | Phase 2+ (consistent classification/ranking) |
| Tool calling | Phase 4 (draft assistant calls my Java methods) |
| Agent loop | Phase 4 (draft assistant reasons across multiple steps) |
| Chain of thought | Phase 4 (explainable draft recommendations) |
| Guardrails | Phase 3+ (system prompt constraints) |
| Streaming | Phase 4+ (responsive UI) |
| Fantasy scoring | ✅ Done (Week 3 Day 4) |
| Position enum | ✅ Done (Week 3 Day 4) |
| Interface extraction | ✅ Done (Week 3 Day 4) — StatLine for scoring abstraction |
| Unit testing (JUnit 5) | ✅ Done (Week 3 Day 4) — ScoringServiceTest |
| External projections | Phase 1 Week 4 (Sleeper projections API) |
| Player scoring table | ✅ Done (Week 4 Day 1) — Flyway V4, entity, repository, wired into sync |
| Entity/DTO separation | Phase 1–2 (new entities get DTOs from start; existing refactored when touched) |
| League settings persistence | Phase 3 (user customization) |
| Lombok | ✅ Done (Week 4 Day 1) — all entities refactored |
| @PrePersist/@PreUpdate | ✅ Done (Week 4 Day 1) — JPA lifecycle callbacks for audit timestamps |
| @Query with JPQL | ✅ Done (Week 4 Day 1) — findDistinctYears() |
| Computed/derived tables | ✅ Done (Week 4 Day 1) — player_scoring, no FK, application-guaranteed integrity |

---

## Things I Don't Need To Worry About

| Concept | Why I skip it |
|---------|---------------|
| **Training models** | I call APIs. ML Engineers train models. Different job. |
| **Attention mechanisms / transformers** | Interesting theory, not required for integration work. |
| **GPU/TPU infrastructure** | Anthropic/OpenAI manage this. |
| **Loss functions / backpropagation** | Model training concepts, not my domain. |
| **RLHF** | How models are aligned. Academic interest, not my job. |
| **Quantization / model compression** | Running models locally. I use cloud APIs. |
| **Fine-tuning** | RAG solves 95% of cases. Expensive, complex, rarely needed. |

---

## Open Questions / Things To Bring To Next Session

- _(track confusions here so I don't lose them)_

---

## Phase 0 — COMPLETE ✅

### Week 1 Status
- [x] Day 1 — Conceptual foundation
- [x] Day 2 — curl experiments + system prompt understanding
- [x] Day 3 — First Java HTTP call to Claude (with proper error handling, Jackson, custom exceptions)
- [x] Day 4 — Error handling experiments — 6 failure modes observed and documented

### Week 2 Status
- [x] Day 1 — Spring Boot project setup, pom.xml deep dive, Maven Wrapper
- [x] Day 2 — First Spring AI call via CommandLineRunner, fail-fast auto-config
- [x] Day 3 — REST endpoint (POST /ask), SLF4J logging, Spring MVC request lifecycle
- [x] Day 4 — Structured output with Java records, auto-retry observed, hallucination experiments
- [x] Day 5 — System prompts in Spring AI, honesty testing, Week 2 wrap-up

### Detours completed
- [x] Detour 1 — Spring Framework deep dive (IoC, DI, beans, ApplicationContext, scopes, Boot vs Spring)
- [x] Detour 2 — Spring MVC request lifecycle (DispatcherServlet, HandlerMapping, HttpMessageConverter)
- [x] Detour 3 — JPA Relationships deep dive (owning side, lazy/eager, N+1, composite keys, @IdClass)

**Hours invested so far:** 43h

---

## Phase 1 — Data Foundation (IN PROGRESS)

**Project:** Read Option (`readoption.app`)
**Repo:** `read-option` (GitHub: public)
**Package:** `app.readoption`

**Stack:**
- Spring Boot 3.5.14
- PostgreSQL 16.14 + pgvector (Docker)
- Spring Data JPA + Hibernate
- Flyway (schema versioning)
- Spring AI 1.1.6 (Anthropic Claude)
- Sleeper API (free NFL data)
- Docker + Docker Compose
- Lombok (compile-time code generation)

### Package naming decision
- Used reverse domain convention: own `readoption.app` → base package `app.readoption`
- Avoids coupling personal name to codebase (`com.sorin.readoption` would be awkward with collaborators or open-sourcing)
- Sub-packages reflect bounded contexts: `app.readoption.player`, `app.readoption.draft`, etc.

### Week 3 Day 1 — Project Setup + Database
**Time:** ~2h

**What I did**
- Generated Spring Boot project from Initializr (6 dependencies: Web, Actuator, JPA, PostgreSQL, Flyway, Anthropic)
- Created `docker-compose.yml` for PostgreSQL 16 + pgvector
- Wrote first Flyway migration (`V1__create_player_table.sql`)
- Created JPA entity (`Player.java`) and repository (`PlayerRepository.java`)
- Debugged port conflict (WSL had PostgreSQL on 5432, Docker mapped to 5433)
- App starts, Flyway runs migration, Hibernate validates schema

### Docker Compose — what I learned
- **Declarative infrastructure** — config in a file, not a command to remember
- `docker-compose.yml` is version-controlled, reproducible, shareable
- `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` env vars only take effect on **first initialization** — if the volume already has data, changing these does nothing. Must `docker compose down -v` to reset.
- Named volumes (`pgdata`) persist data across container restarts
- `-d` flag runs detached (background)
- **Interview line:** *"I use Docker Compose for local development infrastructure. The compose file is declarative, version-controlled, and reproducible. Named volumes persist data across restarts. Anyone cloning the repo runs `docker compose up` and gets an identical database."*

### Port conflict debugging
- WSL was relaying port 5432 via `wslrelay.exe` (PostgreSQL running inside WSL)
- Docker also tried to bind 5432 — Spring Boot connected to WSL's PostgreSQL, not Docker's
- Fix: mapped Docker to 5433 (`"5433:5432"`)
- **Lesson:** `netstat -ano | findstr :PORT` shows all processes on a port. Check PID with `tasklist | findstr PID`. When running Docker on Windows with WSL, watch for port conflicts.

### Flyway — schema migration
- Versioned SQL files in `src/main/resources/db/migration/`
- Naming: `V{number}__{description}.sql` (two underscores)
- On startup: Flyway checks `flyway_schema_history` table, runs any new migrations in order
- **Why not Hibernate `ddl-auto=update`:** can't handle data migrations, no audit trail, makes irreversible guesses. Dangerous in production.
- **My setup:** Flyway manages schema changes, Hibernate set to `validate` (only checks entities match schema, never modifies it)
- **Interview line:** *"Flyway manages database migrations as versioned SQL files in source control. Each migration runs once, in order, tracked in a history table. Hibernate is set to `validate` — it confirms entity mappings match the schema but never modifies it. This gives explicit, reviewable, reproducible schema evolution."*

### JPA Entity design decisions
- **Source system IDs as primary keys** (`id VARCHAR(20)`) — Sleeper API provides stable player IDs, avoids surrogate key mapping in ETL
- **`VARCHAR` over enum for position** — NFL positions can be messy (FB, DB, DL, etc.), flexible for initial ingestion
- **Nullable team** — free agents have no team
- **Indexes on `position`, `team`, `active`** — the columns I'll filter by most often
- **`created_at` / `updated_at` from day one** — adding audit columns retroactively is painful
- **Entity class, not record** — JPA needs no-arg constructor + mutable state for dirty checking. Records are immutable → don't work as JPA entities. Records for DTOs, classes for entities.
- **Interview line:** *"JPA entities can't be records because Hibernate needs a no-arg constructor and mutable state for dirty checking. Records are immutable by design. I use records for DTOs and API responses, and traditional classes for entities."*

### Spring Data JPA Repository
- `PlayerRepository extends JpaRepository<Player, String>` — interface only, no implementation
- Spring generates SQL from method names at runtime: `findByPosition(String)` → `SELECT * FROM player WHERE position = ?`
- Provides CRUD operations (save, findById, findAll, delete) for free
- For complex queries: `@Query` with JPQL or native SQL
- **Interview line:** *"Spring Data JPA derives queries from method names — `findByPositionAndActiveTrue` generates correct SQL automatically. For simple queries this eliminates boilerplate. For complex queries I use `@Query` with JPQL or native SQL."*

### `application.properties` — new concepts
- **`spring.jpa.hibernate.ddl-auto=validate`** — Hibernate checks entities match schema, fails on mismatch. Never `update` in production.
- **`spring.jpa.show-sql=true`** — prints every generated SQL to console. Essential for dev, disable in production.
- **`spring.flyway.locations=classpath:db/migration`** — where Flyway looks for migration files
- **`spring.datasource.url`** — standard JDBC connection string, same pattern as Oracle just different prefix

### Project structure so far
```
read-option/
├── docker-compose.yml                          ← PostgreSQL + pgvector
├── pom.xml
├── README.md
└── src/main/
    ├── java/app/readoption/
    │   ├── ReadOptionApplication.java          ← @SpringBootApplication
    │   ├── player/
    │   │   ├── Player.java                     ← JPA entity (implements Persistable)
    │   │   ├── PlayerRepository.java           ← Spring Data repository
    │   │   ├── PlayerSyncService.java          ← ETL orchestration
    │   │   └── PlayerController.java           ← REST endpoints
    │   └── sleeper/
    │       ├── SleeperClient.java              ← HTTP client for Sleeper API
    │       └── SleeperPlayer.java              ← DTO record for API response
    └── resources/
        ├── application.properties
        └── db/migration/
            └── V1__create_player_table.sql     ← first Flyway migration
```

### Week 3 Day 2 — Sleeper API Integration + Data Sync Pipeline
**Time:** ~2h

**What I built**
- `SleeperClient` — HTTP client fetching all NFL players from `api.sleeper.app`
- `SleeperPlayer` — Java record DTO for the API response with `@JsonProperty` for snake_case mapping
- `PlayerSyncService` — orchestrates fetch → filter → map → save
- `PlayerController` — REST endpoints: `POST /api/players/sync`, `GET /api/players`, `GET /api/players/position/{position}`
- Full ETL pipeline: 12,187 players fetched → filtered to 3,217 active fantasy-relevant players → persisted to PostgreSQL

**Data quality issues encountered (real ETL problems)**
1. **`NullPointerException` from `Set.of().contains(null)`** — `Set.of()` throws NPE on `contains(null)` unlike `HashSet`. Fix: separate null check into its own filter step before calling `contains()`.
2. **`fullName` was null for some players** — entity had `nullable = false`. Fix: construct fullName from firstName + lastName when missing, defaulting to "Unknown".
3. **Lesson:** External API data is never as clean as docs suggest. ETL layer must cleanse and default, not just pass through.
- **Interview line:** *"`Set.of()` creates truly unmodifiable collections that throw NPE on `contains(null)`. When filtering external API data where any field might be null, I separate null checks into their own filter steps rather than relying on short-circuit evaluation in compound conditions."*

**The SELECT-per-entity performance problem**
- With `show-sql=true`, noticed Hibernate doing a SELECT for every player before INSERT (~3,600 queries for 1,800 players)
- **Root cause:** Spring Data's `save()` can't tell if a manually-assigned ID entity is new or existing, so it SELECTs to check
- **Fix:** Implement `Persistable<String>` interface on the entity, override `isNew()` method with a `@Transient` flag
- Result: eliminated ~1,800 unnecessary SELECT queries
- **Interview line:** *"When using manually assigned IDs with Spring Data JPA, `save()` does a SELECT before every INSERT to determine if the entity is new. For bulk operations this is devastating. The fix is implementing `Persistable` and overriding `isNew()` to tell Spring Data directly whether to INSERT or UPDATE, eliminating the extra round-trips."*

**Upsert logic (idempotent sync)**
- First sync: all INSERTs (new players)
- Second sync: all UPDATEs (existing players, refreshed data)
- Load existing IDs in one query, mark entities with `markExisting()` before save
- `@Transactional` ensures atomicity: all-or-nothing saves

**`@Transactional` — first real use**
- Spring creates a proxy around the bean that opens a transaction before the method and commits/rolls back after
- If `saveAll()` fails at player 1,200, all 1,200 are rolled back — no partial writes
- **Gotcha:** only works when called from outside the class — self-calls bypass the proxy (AOP limitation)
- **Interview line:** *"`@Transactional` ensures atomicity via AOP proxy. Important: it only works on calls from outside the class. Internal method calls bypass the proxy, so a `@Transactional` method calling another `@Transactional` method on the same class won't create a new transaction."*

**Other patterns used**
- **`@JsonProperty` for snake_case ↔ camelCase mapping** — explicit per-field mapping is more readable than global naming strategy
- **`FAIL_ON_UNKNOWN_PROPERTIES = false`** — ignore unknown JSON fields from external APIs. Defensive parsing: take what you need, ignore the rest. Prevents breakage when the API adds new fields.
- **`@JsonIgnore`** — prevents internal fields (`isNew` from `Persistable`) from leaking into API responses
- **`@Value("${property}")`** — externalized Sleeper API URL to `application.properties` for visibility and testability
- **`@RequestMapping` class-level prefix** — `/api/players` base path for all endpoints in the controller
- **`@PathVariable`** — extracts from URL path (`/position/QB` → `position = "QB"`)
- **DTO vs Entity separation** — `SleeperPlayer` (record, API shape) vs `Player` (class, database shape). If Sleeper changes their API, only the DTO and mapping method change.

**Architectural decisions documented**
- No version/audit history yet — add via Flyway migration when there's a real use case (YAGNI)
- No API versioning yet (`/api/players` not `/api/v1/players`) — no consumers to protect
- Database resets OK during early development; new migrations only once schema stabilizes
- External URLs in properties, not hardcoded — makes dependencies visible and testable

**Optional — when to use and when not to**
- Optional is for **method return types** signaling "might not exist" (e.g., `findById` returns `Optional<Player>`)
- NOT for fields, parameters, or DTOs — adds verbosity without benefit, doesn't serialize cleanly, not Serializable
- For null-safe filtering of external data, explicit null checks are simpler and more direct
- **Interview line:** *"Optional is for method return types where absence is a normal case. I don't use it for fields or DTOs — it adds ceremony without benefit, doesn't serialize cleanly with Jackson, and isn't what the API designers intended. For nullable external data, explicit null checks are simpler."*

---

### Week 3 Day 3 — Player Stats: Schema, Entities, JPA Relationships + Stats Sync Pipeline
**Time:** ~5h

**What I built**
- `V2__create_player_stats_table.sql` — Flyway migration with composite primary key and foreign key
- `PlayerStatsId` — composite ID class for JPA (`@IdClass`)
- `PlayerStats` — JPA entity with `@ManyToOne` relationship, `Persistable<PlayerStatsId>`
- `PlayerStatsRepository` — Spring Data JPA with derived queries
- `SleeperStatsData` — DTO record for nested stats object from Sleeper API
- `SleeperPlayerStats` — DTO record for top-level stats response
- `PlayerStatsSyncService` — ETL pipeline: fetch → filter → map (Double→Integer) → upsert
- `PlayerStatsController` — REST endpoints for sync and query
- Extended `SleeperClient` with `fetchStats(int season)` method
- Loaded 5 seasons of stats (2020–2024), ~15,000+ stat lines total

**Schema design decisions**
- **Seasonal grain, not weekly** — draft decisions are season-level. Weekly data adds complexity without value for Phase 1. Can add `player_weekly_stats` via V3 migration later.
- **Composite primary key (`player_id, year`)** — natural key, no surrogate needed. The combination permanently and uniquely identifies a stat line. PK also gives a free uniqueness constraint. Chose composite over surrogate because: combination is stable, only two columns, nothing else will reference `player_stats` by ID.
- **Foreign key on `player_id`** — database enforces referential integrity. A stat line without a valid player is rejected at the DB level, not just in application code.
- **`games` vs `games_played`** — `games` = total games in the NFL season (16 pre-2021, 17 post-2021), `games_played` = how many that player appeared in. Both are needed for per-game averages and fair cross-season comparison.
- **Kickers and DST excluded** — lowest draft value positions, most leagues stream them weekly. Skipped for MVP, can add via wide table later (Option A) or separate tables (Option B).
- **Stat columns nullable** — position-dependent. A RB has NULLs for passing stats, and that's correct. NULL means "not applicable," not "we forgot."

**Sleeper stats API — differences from players API**
- Stats endpoint: `https://api.sleeper.app/stats/nfl/{season}?season_type=regular` — no `/v1/` prefix, different from players endpoint
- Response is a JSON **array** (not a map like the players endpoint). Each element has `player_id`, `team`, and a nested `stats` object.
- Stats values are floating point (`17.0`, `44.0`) not integers — requires Double in DTOs, Integer in entities
- Nested response structure requires two DTO records: `SleeperPlayerStats` (top-level) and `SleeperStatsData` (nested stats)
- Returns ~50 stat fields per player, we use ~15 — `@JsonIgnoreProperties(ignoreUnknown = true)` ignores the rest

**ETL pipeline for stats (same patterns as player sync)**
- Filter to known player IDs from `player` table (FK constraint would reject unknowns, but filtering in code avoids wasting time on thousands of irrelevant records)
- Upsert via `Persistable.isNew()` + `markExisting()` — first sync INSERTs, subsequent syncs UPDATE
- `@Transactional` for atomicity on batch saves
- Season games count derived from year (`season >= 2021 ? 17 : 16`) since API doesn't provide it
- `TypeReference<List<SleeperPlayerStats>>` for Jackson deserialization of generic list — same concept as `ParameterizedTypeReference` in Spring

**Hibernate lazy proxy serialization error — first encounter**
- Returned `PlayerStats` entity directly from controller via `GET /api/stats/player/{id}`
- Jackson hit the lazy `Player` proxy → `ByteBuddyInterceptor` → 500 error
- Fix: `@JsonIgnore` on the `player` field — it's for Java-side navigation, not API responses
- Proper production fix: return DTOs from controllers, not entities. Entities are database objects, not API contracts. Noted for future improvement.
- **Interview line:** *"Lazy-loaded JPA relationships use Hibernate proxies — placeholder objects that load the real entity only when accessed. If you serialize an entity directly to JSON, Jackson hits the proxy and fails. The immediate fix is `@JsonIgnore`, but the proper fix is returning DTOs from controllers — it decouples your API shape from your database schema."*

**Mistakes I made and lessons**
1. **`Persistable<PlayerStats>` instead of `Persistable<PlayerStatsId>`** — the generic type is the ID type, not the entity type. `getId()` returns the ID object.
2. **`PlayerStatsId` had `Player player` instead of `String playerId`** — IdClass uses raw key types, not entity references. For `@ManyToOne` IDs, use the PK type of the related entity.
3. **Flyway migration had copy-paste errors** — wrong comment ("V1: Core player table"), index names didn't match columns (`idx_player_position` on `player_id`). Flyway migrations are permanent history — can't edit once run.
4. **Missing FK constraint in first draft** — `player_id` was not declared as a foreign key. Database should enforce referential integrity, not just the application.
5. **Missing `equals()`/`hashCode()`/`Serializable` on IdClass** — JPA spec requires all three for identity checks and caching. Without `equals`/`hashCode`, lookups break silently.

### Project structure (updated)
```
read-option/
├── docker-compose.yml
├── pom.xml
└── src/main/
    ├── java/app/readoption/
    │   ├── ReadOptionApplication.java
    │   ├── player/
    │   │   ├── Player.java                     ← JPA entity (Persistable)
    │   │   ├── PlayerRepository.java
    │   │   ├── PlayerSyncService.java
    │   │   └── PlayerController.java
    │   ├── playerstats/
    │   │   ├── PlayerStats.java                ← JPA entity (Persistable, composite key, @ManyToOne)
    │   │   ├── PlayerStatsId.java              ← @IdClass (Serializable, equals/hashCode)
    │   │   ├── PlayerStatsRepository.java
    │   │   ├── PlayerStatsSyncService.java     ← ETL: fetch → filter → map → upsert
    │   │   └── PlayerStatsController.java
    │   └── sleeper/
    │       ├── SleeperClient.java              ← HTTP client (players + stats endpoints)
    │       ├── SleeperPlayer.java              ← DTO for player API
    │       ├── SleeperPlayerStats.java         ← DTO for stats API (top-level)
    │       └── SleeperStatsData.java           ← DTO for nested stats object
    └── resources/
        ├── application.properties
        └── db/migration/
            ├── V1__create_player_table.sql
            └── V2__create_player_stats_table.sql
```

---

### Week 3 Day 4 — Fantasy Scoring Engine: Architecture Decisions + Implementation
**Time:** ~4h

**What I did**
- Analyzed 5 interconnected architectural decisions for the fantasy scoring system (formula location, result storage, format handling, computation timing, PPG storage)
- Defined the role split: external sources predict performance, Claude optimizes draft strategy
- Built: `Position` enum, `StatLine` interface, `ScoringFormat` enum, `ScoringResult` record, `LeagueSettings` record, `ScoringService`
- Wrote first unit test in the project: `ScoringServiceTest` (8 tests, no Spring/DB required)
- Added `V3__add_fumbles_lost_to_player_stats.sql` Flyway migration
- Updated `PlayerStats` to implement `StatLine`, added `fumblesLost` field
- Updated `SleeperStatsData` to map `fum_lost` from Sleeper API
- Re-synced all 5 seasons to populate `fumbles_lost`

**Architectural decisions and reasoning**

1. **Where the scoring formula lives: Java service (not SQL)**
   - Scoring is business logic with rules, edge cases, and format variations — Java's job
   - Testable with plain JUnit, no database needed
   - Banking parallel: transaction fee calculation wouldn't live in a SQL view
   - **Interview line:** *"The scoring formula is business logic — it has rules, format variations, and edge cases. It belongs in a Java service where it's unit-testable without a database, not in a SQL view where it's buried in migration files and harder to test."*

2. **Where results are stored: separate `player_scoring` table (not columns on `player_stats`)**
   - Originally considered adding columns to `player_stats`, but projections changed the picture
   - Both historical stats and projected stats (coming next) need fantasy points calculated
   - Separate table avoids duplicating computed columns across two source tables
   - Adding scoring formats = new rows, not schema changes
   - **Interview line:** *"I chose a separate scoring table because both historical stats and projected stats need fantasy points calculated. A separate table with a source discriminator avoids duplicating computed columns across two source tables and lets me add scoring formats as new rows rather than schema changes."*

3. **Multiple scoring formats: enum with fields (Strategy pattern)**
   - Only two rules vary between formats: points per reception (0/0.5/1.0) and passing TD value (4/6)
   - Each enum value carries its own multipliers — no switch statement in the service
   - Adding a format = one line in the enum, zero changes to the scoring service
   - MVP uses `STANDARD_6PT`, other formats defined but unused
   - **Interview line:** *"I put the varying scoring rules directly on the enum values rather than using a switch in the service. Each format carries its own multipliers — adding a new format is one line in the enum, zero changes to the scoring service. It's the Strategy pattern expressed through an enum."*

4. **When to compute: during sync + separate recompute endpoint**
   - Sync computes as it saves (data always complete)
   - Recompute endpoint exists for formula iterations during development
   - Both paths call the same `ScoringService.calculate()` — no duplication
   - In production, only the sync path matters

5. **PPG: stored, not computed at query time**
   - Pre-computed means queries, API responses, and Claude prompts all use the same number
   - Division-by-zero edge case (games_played = 0) handled once in the service
   - 6 columns total (3 formats × total + PPG) — trivial storage cost

**Projections approach — the key architectural decision**
- **External source (Sleeper projections API), not building own prediction engine**
- Player performance prediction is a solved problem at scale (ESPN, FantasyPros have dedicated teams)
- Claude's value is strategic reasoning: given projected values + user's draft strategy, optimize picks
- App architecture: **data in (Sleeper projections) → scoring engine (Java) → strategy engine (Claude) → advice out**
- Example: Mike Evans changing teams, aging — projection models already factor this in. Claude takes those projections and reasons about draft position value and roster construction.
- **Interview line:** *"I chose to consume projections from established providers rather than build a prediction engine. Player performance prediction is a solved problem at scale. The LLM's value is in strategic reasoning: given projected player values and a user's draft strategy, optimize their picks. That's a clean separation — data providers predict, the scoring engine converts to fantasy points, and Claude optimizes the draft."*

**Entity/DTO separation strategy**
- Currently returning JPA entities from controllers (with `@JsonIgnore` band-aids)
- Decision: DTOs from the start on new entities, refactor existing when we touch them
- No separate refactoring sprint — codebase migrates organically as features evolve
- Trigger: when API response shape diverges from any single entity (composite responses, filtered fields, computed values)
- **Interview line:** *"I separate API response DTOs from JPA entities because they serve different masters — entities model database structure, DTOs model what the consumer needs. I introduce DTOs when building new features rather than doing a bulk refactoring pass. The trigger is when the API response shape diverges from any single entity."*

**What I built**

**`Position` enum** — type-safe fantasy positions
- Enum with `QB, RB, WR, TE, K, DEF` for application logic (league settings, scoring, draft strategy)
- `Player` entity keeps `position` as String for flexible external API ingestion
- `FANTASY_POSITION_NAMES` constant (`Set<String>`) for sync filtering — replaces hardcoded set in `PlayerSyncService`
- **Interview line:** *"I keep the Player entity's position as a String for flexible ingestion from external APIs — Sleeper returns positions like FB and DL that don't map to fantasy categories. Application logic uses a Position enum for type safety. The entity ingests broadly, the enum constrains narrowly."*

**`StatLine` interface** — abstraction over scorable stats
- Defines getter methods for all scoring-relevant stats
- `PlayerStats` implements it (historical data). Future `PlayerProjection` will implement it too.
- Scoring service depends on the interface, not on any concrete entity
- Method names match existing `PlayerStats` getter names (extracted interface pattern)
- Uses `Integer` (not `int`) because stats are position-dependent — null means "not applicable"
- **Interview line:** *"I used an interface because both historical stats and projected stats share the same statistical structure. The scoring service operates on the abstraction, so adding projections later requires zero changes to the scoring logic — just a new class that implements StatLine."*

**`ScoringFormat` enum** — Strategy pattern via enum
- Each value holds its own `pointsPerReception` and `passingTdPoints`
- All other rules (rushing yards, receiving TDs, etc.) are constants in the service
- Six formats defined (all combinations of Standard/Half-PPR/PPR × 4pt/6pt passing TD)
- MVP uses `STANDARD_6PT`

**`ScoringResult` record** — immutable calculation result
- Bundles `totalPoints` and `pointsPerGame` as `BigDecimal`
- Returned by `ScoringService.calculate()` — prevents caller from computing total and PPG independently with different parameters

**`LeagueSettings` record** — roster configuration for draft strategy
- Captures: teams, position slots (QB/RB/WR/TE), flex slots with eligible positions, bench, scoring format
- `defaultSettings()` factory method: 12-team, 1QB/2RB/2WR/1TE/1FLEX(RB,WR)/6bench, Standard 6pt
- Not persisted yet — becomes a database entity in Phase 3 (user customization)
- Will be injected into Claude's prompt for draft optimization
- **Interview line:** *"Roster configuration affects draft strategy, not scoring. A player's fantasy points are the same regardless of league format — what changes is their positional value relative to scarcity. I modeled league settings as a record with a factory method now, deferring persistence until the user customization phase."*

**`ScoringService`** — the scoring formula
- One `calculate(StatLine, ScoringFormat)` method returning `ScoringResult`
- `BigDecimal` arithmetic with explicit `HALF_UP` rounding at scale 2
- Constants as `static final BigDecimal` using String constructor (`new BigDecimal("0.04")` not `new BigDecimal(0.04)`)
- Single `points()` helper method handles null-safe multiplication for all scoring rules
- PPG edge case: `gamesPlayed <= 0` returns zero PPG (no `ArithmeticException`)
- `@Service` annotation: currently no dependencies (could be static), but Spring bean enables future DI
- **Interview line:** *"`new BigDecimal(\"0.04\")` uses the String constructor for exact representation. The double constructor `new BigDecimal(0.04)` can produce `0.04000000000000000083...` because 0.04 isn't exactly representable in IEEE 754. The String constructor parses the literal decimal value."*

**`ScoringServiceTest`** — first unit test in the project
- 8 tests covering: QB scoring, RB scoring, PPR vs Standard comparison, 4pt vs 6pt passing TD, all nulls, zero games played, half-PPR verification, 2pt conversions + fumbles
- Uses anonymous `StatLine` implementation — no JPA, no Spring, no database
- `@DisplayName` for readable test output
- Each test verifies one scoring rule or edge case with hand-calculated expected values
- **Interview line:** *"The scoring service is tested with an anonymous implementation of the StatLine interface — no JPA entity, no database, no Spring context. This is the benefit of depending on an interface rather than a concrete entity class. Same code path, different implementations."*

**Mistakes and things I learned**

1. **`int` vs `Integer` for interface implementation**
   - `PlayerStats` had `int gamesPlayed` but `StatLine` declared `Integer getGamesPlayed()`
   - Java does NOT autobox return types for method overriding — `int` does not satisfy `Integer`
   - Fix: changed `gamesPlayed` from `int` to `Integer` in the entity
   - **Interview line:** *"Primitive return types don't satisfy wrapper return types in interface implementation — Java's autoboxing works for assignments and parameters but not for method override signatures."*

2. **StatLine field names didn't match entity**
   - Initially wrote `getPassYards()` in interface, but entity has `getPassingYards()`
   - Interface should adapt to the existing entity (extracted interface pattern), not the other way around
   - Lesson: check actual code before designing the interface

3. **Missing `fumblesLost` in entity, missing `twoPtConv` in interface**
   - Entity had `twoPtConv` (2-point conversions worth 2 fantasy points) that I missed
   - Entity was missing `fumblesLost` (-2 points per fumble) — added via V3 Flyway migration
   - Re-synced all seasons after adding the column and updating the DTO

**Hibernate naming strategy — `CamelCaseToUnderscoresNamingStrategy`**
- Spring Boot auto-configures Hibernate to convert camelCase field names to snake_case column names
- `fumblesLost` → `fumbles_lost`, `passingYards` → `passing_yards`, `createdAt` → `created_at`
- No `@Column` annotation needed when the mapping follows this convention
- Without Spring Boot (plain Hibernate), field names map as-is — Boot overrides this default
- `@Column(name = "...")` only needed when the mapping doesn't follow convention or for constraints
- **Interview line:** *"Spring Boot configures Hibernate's physical naming strategy to convert camelCase field names to snake_case column names automatically. You only need `@Column` when the mapping doesn't follow the convention or when you need to set constraints."*

### Project structure (updated)
```
read-option/
├── docker-compose.yml
├── pom.xml
└── src/
    ├── main/java/app/readoption/
    │   ├── ReadOptionApplication.java
    │   ├── player/
    │   │   ├── Player.java                     ← JPA entity (Persistable)
    │   │   ├── PlayerRepository.java
    │   │   ├── PlayerSyncService.java          ← uses Position.FANTASY_POSITION_NAMES
    │   │   └── PlayerController.java
    │   ├── playerstats/
    │   │   ├── PlayerStats.java                ← implements Persistable + StatLine
    │   │   ├── PlayerStatsId.java
    │   │   ├── PlayerStatsRepository.java
    │   │   ├── PlayerStatsSyncService.java
    │   │   └── PlayerStatsController.java
    │   ├── scoring/                            ← NEW PACKAGE
    │   │   ├── Position.java                   ← enum (QB, RB, WR, TE, K, DEF)
    │   │   ├── StatLine.java                   ← interface for scorable stats
    │   │   ├── ScoringFormat.java              ← enum with Strategy pattern
    │   │   ├── ScoringResult.java              ← immutable result record
    │   │   ├── ScoringService.java             ← formula logic (@Service)
    │   │   └── LeagueSettings.java             ← roster config record
    │   └── sleeper/
    │       ├── SleeperClient.java
    │       ├── SleeperPlayer.java
    │       ├── SleeperPlayerStats.java
    │       └── SleeperStatsData.java           ← updated: added fumblesLost
    ├── resources/
    │   ├── application.properties
    │   └── db/migration/
    │       ├── V1__create_player_table.sql
    │       ├── V2__create_player_stats_table.sql
    │       └── V3__add_fumbles_lost_to_player_stats.sql  ← NEW
    └── test/java/app/readoption/
        └── scoring/
            └── ScoringServiceTest.java         ← 8 unit tests, no Spring context
```

---

### Week 4 Day 1 — Player Scoring Persistence + Lombok Refactor
**Time:** ~4h

**What I built**
- `V4__create_player_scoring_table.sql` — Flyway migration with 3-column composite PK
- `PlayerScoringId` — composite ID class (Lombok: @Getter, @NoArgsConstructor, @AllArgsConstructor, @EqualsAndHashCode)
- `PlayerScoring` — JPA entity with Persistable, @Builder, @PrePersist/@PreUpdate
- `PlayerScoringRepository` — Spring Data JPA with derived queries
- `PlayerScoringService` — orchestrates scoring persistence (compute all 6 formats per stat line)
- `PlayerScoringController` — compute, recompute, and query endpoints
- Wired scoring into `PlayerStatsSyncService` — auto-computes after every stats sync
- Added `findDistinctYears()` JPQL query to `PlayerStatsRepository`
- Refactored ALL entities and ID classes to Lombok (@Getter, @Setter, @Builder, etc.)
- Switched from `show-sql=true` to SLF4J-based Hibernate SQL logging
- Total: ~115,584 scoring rows (19,264 stat lines × 6 formats)

**Design decisions**

1. **3-column PK, no source discriminator**
   - PK: `(player_id, year, scoring_format)` — not 4 columns
   - Rejected `source` column (HISTORICAL vs PROJECTED) because the year itself is the discriminator — past years are always historical, current year is always projected
   - YAGNI: the source column solves a problem the app doesn't have
   - **Interview line:** *"I considered a source discriminator column but rejected it — the year inherently distinguishes historical from projected data. Adding a column to 'future-proof' against a problem that doesn't exist violates YAGNI and complicates every query."*

2. **No FK on computed table**
   - `player_scoring` is derived — always populated by application logic, never manually edited
   - Both historical stats and future projections will score into this table
   - A FK to `player_stats` would break when projection scores (no matching stat line) arrive
   - Application guarantees data integrity through the scoring pipeline
   - **Interview line:** *"Foreign keys enforce referential integrity at the database level, which is critical for source-of-truth tables. For derived or computed tables that are always populated by application logic and can be fully recomputed, FKs add constraint management overhead without meaningful safety gains."*

3. **Compute all 6 formats, not just MVP**
   - 15,000 × 6 = 90,000 rows is trivial for PostgreSQL
   - Pre-computed means query endpoints can serve any format without recomputing
   - Adding a format = adding an enum value + recompute, no schema changes

4. **Denormalized `games_played`**
   - Copied from `player_stats` into `player_scoring` for leaderboard filtering ("minimum 10 games")
   - Safe because scoring rows are always computed alongside the stat line — values stay consistent
   - Avoids a JOIN on the most common query pattern

**Leftmost prefix rule for composite indexes**
- PK index on `(player_id, year, scoring_format)` already covers queries filtering by `player_id` alone
- PostgreSQL can use the leftmost columns of a composite index
- Separate index on `player_id` would be redundant
- Added `idx_scoring_year_format` on `(year, scoring_format)` for leaderboard queries — PK index can't help here because `year` isn't the leftmost column
- **Interview line:** *"A composite index on (A, B, C) supports queries filtering on A, on A+B, and on A+B+C — the leftmost prefix rule. A separate index on A alone would be redundant. But a query filtering only on B or C can't use this index — that's why I added a separate index on (year, scoring_format) for leaderboard queries."*

**`@PrePersist` and `@PreUpdate` — JPA lifecycle callbacks**
- Methods that JPA calls automatically before INSERT (`@PrePersist`) and before UPDATE (`@PreUpdate`)
- Standard way to handle audit timestamps — entity owns its own timestamp logic
- `DEFAULT CURRENT_TIMESTAMP` in migrations only fires on INSERT, not UPDATE (would need a DB trigger for that)
- Applied to all three entities: Player, PlayerStats, PlayerScoring
- **Interview line:** *"`@PrePersist` and `@PreUpdate` are JPA lifecycle callbacks that fire before INSERT and UPDATE respectively. They're the standard way to handle audit timestamps in JPA — the entity owns its own timestamp logic rather than relying on database-level defaults or triggers."*

**`@Query` with JPQL — custom queries beyond method naming**
- `findDistinctYears()` can't be expressed through Spring Data's method naming convention
- JPQL operates on entity classes and field names, not tables and columns: `SELECT DISTINCT ps.year FROM PlayerStats ps`
- Hibernate translates JPQL to SQL using entity mappings
- Return type matches the query: `SELECT ps.year` returns Integer, method returns `List<Integer>`
- For database-specific features (window functions, CTEs): `nativeQuery = true` drops to raw SQL
- **Interview line:** *"`@Query` with JPQL is for queries that can't be expressed through Spring Data's method naming convention — projections, aggregations, complex joins. JPQL operates on entity classes and field names, not tables and columns, so it stays portable across databases."*

**`ResponseEntity` — explicit HTTP response control**
- Query endpoints return objects directly (Spring auto-wraps with 200 OK + JSON)
- Action endpoints (compute, recompute) use `ResponseEntity.ok("message")` for informative responses
- Gives explicit control over status code, headers, and body when needed
- **Interview line:** *"`ResponseEntity` gives explicit control over the HTTP status code, headers, and body. For CRUD reads I return objects directly — Spring auto-wraps with 200 OK. For actions like recompute or sync, I use `ResponseEntity` to return an informative message with an explicit status."*

**`@Transactional` self-call proxy bypass — deep understanding**
- Spring wraps beans in AOP proxies. `@Transactional` behavior lives on the proxy, not the actual object.
- External calls go through the proxy → transaction management applied
- Internal calls via `this` go directly to the real object → proxy is bypassed → `@Transactional` annotation is invisible
- In `PlayerScoringService`: `recomputeAllSeasons()` calls `computeAndSaveForSeason()` internally — one big transaction wraps all seasons. If season 2022 fails, all seasons roll back (desirable for recompute).
- When `computeAndSaveForSeason()` is called from the controller (external call), it gets its own transaction.
- Fix for independent transactions: extract into a separate bean so each call goes through its own proxy.
- **Interview line:** *"Spring's `@Transactional` works through AOP proxies that wrap the bean. External calls go through the proxy and get transaction management. Internal self-calls via `this` bypass the proxy entirely — the annotation is invisible. If I need independent transactions on internal calls, I extract the method into a separate bean so each call gets its own proxy."*

**Lombok — production-standard boilerplate elimination**
- Compile-time annotation processor — generates code during compilation, absent at runtime
- Marked `<optional>true</optional>` in Maven — not in the packaged jar
- IntelliJ requires annotation processing enabled + Lombok plugin
- Applied to all entities and ID classes in the project

**Lombok annotations for JPA — safe vs dangerous:**

| Annotation | Safe on entities? | Notes |
|---|---|---|
| `@Getter` | Yes | Always safe |
| `@Setter` | Yes | Always safe |
| `@NoArgsConstructor` | Yes | JPA requires it |
| `@AllArgsConstructor` | Yes | Needed by `@Builder` |
| `@Builder` | Yes | Use `@Builder.Default` for field initializers |
| `@ToString` | Careful | Exclude lazy relationships with `@ToString.Exclude` |
| `@EqualsAndHashCode` | ID classes only | Never on entities with lazy fields |
| `@Data` | **No** | Includes dangerous `@EqualsAndHashCode` on all fields |

**Why `@Data` is dangerous on JPA entities:**
- `@Data` = `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor`
- `@EqualsAndHashCode` on all fields breaks Hibernate Set-based collections (hash changes after entity is added)
- Can trigger `LazyInitializationException` when `equals()` touches lazy-loaded fields
- **Interview line:** *"Never use `@Data` on JPA entities. It generates `equals` and `hashCode` using all fields, which breaks Hibernate's Set-based collections and can trigger lazy loading exceptions. Use `@Getter` and `@Setter` explicitly. If you need `equals`/`hashCode` on an entity, base it on the business key — never include mutable or lazy fields."*

**`@Builder.Default` — the critical gotcha**
- Without it, `@Builder` ignores field initializers (`= true`, `= new ArrayList<>()`)
- The builder has its own construction path that doesn't go through field initialization
- `isNew = true` becomes `isNew = false` (boolean default) without `@Builder.Default`
- Result: Persistable silently regresses to SELECT-before-INSERT behavior
- **Interview line:** *"`@Builder.Default` preserves field initializers when using Lombok's builder. Without it, the builder ignores `= true` on a boolean field and defaults to `false`. This is a common Lombok gotcha — the builder bypasses field initializers because it uses its own generated constructor."*

**`show-sql` vs SLF4J SQL logging**
- `spring.jpa.show-sql=true` prints to stdout, bypassing the logging framework — no timestamps, no log levels
- Production approach: `logging.level.org.hibernate.SQL=DEBUG` routes through SLF4J
- `logging.level.org.hibernate.type.descriptor.sql.BasicBinder=DEBUG` shows bind parameter values
- Both set to WARN for normal development, toggle to DEBUG when investigating specific queries
- **Interview line:** *"`spring.jpa.show-sql` prints SQL to stdout, bypassing the logging framework. In production, route Hibernate SQL through SLF4J by configuring `logging.level.org.hibernate.SQL=DEBUG`. This way SQL logging is controlled by the same logging infrastructure as everything else."*

**Scoring data validation**
- 115,584 total scoring rows across 5 seasons × 6 formats
- Validated Josh Allen (player 4046) 2021 season: STANDARD_4PT (374.66) vs STANDARD_6PT (448.66) — 74-point gap ÷ 2 = 37 passing TDs. Matches real NFL data (36 TDs + 1 rushing/receiving TD scored differently). Scoring engine confirmed correct.

**Mistakes caught in code review**
1. **Missing `@Builder.Default` on `isNew`** in Player and PlayerStats — had it in PlayerScoring but forgot the other two entities. Without it, builder defaults `isNew` to `false`, silently breaking the Persistable upsert optimization.
2. **`passesCompleted` dropped from builder chain** in `PlayerStatsSyncService` — refactoring from setters to builder lost one field. Would have nulled that column for all rows.
3. **Missing `@JsonIgnore` on `getId()`** — `Persistable.getId()` serialized a redundant `id` object in JSON responses. Same pattern as `isNew()` which was already `@JsonIgnore`'d.

### Project structure (updated)
```
read-option/
├── docker-compose.yml
├── pom.xml                                     ← added Lombok dependency
└── src/
    ├── main/java/app/readoption/
    │   ├── ReadOptionApplication.java
    │   ├── player/
    │   │   ├── Player.java                     ← Lombok + @PrePersist/@PreUpdate
    │   │   ├── PlayerRepository.java
    │   │   ├── PlayerSyncService.java          ← uses Builder
    │   │   └── PlayerController.java
    │   ├── playerstats/
    │   │   ├── PlayerStats.java                ← Lombok + @PrePersist/@PreUpdate
    │   │   ├── PlayerStatsId.java              ← Lombok (@EqualsAndHashCode)
    │   │   ├── PlayerStatsRepository.java      ← added findDistinctYears() @Query
    │   │   ├── PlayerStatsSyncService.java     ← uses Builder + triggers scoring
    │   │   └── PlayerStatsController.java
    │   ├── playerscoring/                      ← NEW PACKAGE
    │   │   ├── PlayerScoring.java              ← Lombok, Persistable, @PrePersist/@PreUpdate
    │   │   ├── PlayerScoringId.java            ← Lombok (@EqualsAndHashCode)
    │   │   ├── PlayerScoringRepository.java
    │   │   ├── PlayerScoringService.java       ← compute all formats, recompute all seasons
    │   │   └── PlayerScoringController.java    ← compute/recompute/query endpoints
    │   ├── scoring/
    │   │   ├── Position.java
    │   │   ├── StatLine.java
    │   │   ├── ScoringFormat.java
    │   │   ├── ScoringResult.java
    │   │   ├── ScoringService.java
    │   │   └── LeagueSettings.java
    │   └── sleeper/
    │       ├── SleeperClient.java
    │       ├── SleeperPlayer.java
    │       ├── SleeperPlayerStats.java
    │       └── SleeperStatsData.java
    ├── resources/
    │   ├── application.properties              ← SLF4J SQL logging config
    │   └── db/migration/
    │       ├── V1__create_player_table.sql
    │       ├── V2__create_player_stats_table.sql
    │       ├── V3__add_fumbles_lost_to_player_stats.sql
    │       └── V4__create_player_scoring_table.sql  ← NEW
    └── test/java/app/readoption/
        └── scoring/
            └── ScoringServiceTest.java
```

### Week 3 Status
- [x] Day 1 — Project setup, Docker Compose, Flyway V1, Player entity + repository
- [x] Day 2 — Sleeper API integration, PlayerSyncService, ETL pipeline (3,217 players)
- [x] Day 3 — Player stats: schema, entities, JPA relationships, stats sync (5 seasons, ~15k stat lines)
- [x] Day 4 — Fantasy scoring engine: 5 architectural decisions, ScoringService, StatLine interface, Position enum, first unit tests, V3 migration (fumbles_lost)

### Week 4 Status
- [x] Day 1 — `player_scoring` table (Flyway V4), entity with Lombok, scoring pipeline wired into stats sync, recompute endpoint, Lombok refactor across all entities
- [ ] Day 2 — `player_projections` table (Flyway V5), Sleeper projections fetch, sync pipeline
- [ ] Day 3 — Score projections through ScoringService, store results
- [ ] Day 4 — Query endpoints: top N by projected points, player detail with historical trend + projection

---

## Week 2 — Spring AI

### Why Spring AI matters for my career
- Spring AI is the official Spring framework abstraction over LLM providers (Anthropic, OpenAI, Mistral, Ollama, etc.)
- Released as 1.0 GA in 2025 — enterprises adopting it now
- Mirrors patterns I already know from Spring Data / Spring Web
- Most Java + AI job postings now mention it
- Strong CV signal: "Spring AI" with a real project is a small group

### Mental model
- Spring AI is an **abstraction over multiple LLM providers** — write once, swap providers via config
- Same Claude underneath; less boilerplate, retries/timeouts built in, structured output supported
- Does NOT replace my Week 1 understanding of the underlying API — abstraction sits on top of it
- Not magic: under the hood it's still HTTP calls to Anthropic

### Decision: new project vs convert claude-playground
- Going with a **new project** (`spring-ai-playground`)
- Keeps `claude-playground` as a frozen reference — "the hard way" for comparison
- Two repos in the portfolio show progression

---

## 🎯 Spring Framework Deep Dive (Detour 1)

Done before starting Spring AI to close mental-model gaps. Below are the takeaways I want to be able to recite cleanly in an interview.

### The core idea — one sentence
> Spring is a container that manages object lifecycles via dependency injection.

Everything else (annotations, scopes, AOP, MVC, Boot) descends from that one idea.

### Inversion of Control (IoC)
- The framework manages **both creation and wiring** of objects (not just creation)
- I stop calling `new` for application components; Spring does it
- Decouples objects from how they're constructed → testable, evolvable
- **Interview line:** *"IoC means the framework manages the creation and wiring of objects instead of the application code doing it manually. The benefit is that as the app grows, I don't maintain a giant init sequence — I declare what each component needs and Spring resolves it."*

### Dependency Injection (DI) — the mechanism
- Constructor injection (preferred), setter injection (legacy), field injection (anti-pattern)
- **Constructor injection has 4 benefits:**
  1. Testable without Spring (construct directly with mocks)
  2. `final` fields → immutability
  3. Thread safety as a consequence of immutability
  4. **Explicit dependencies** — constructor signature documents needs; oversized constructor is a visible code smell
- My existing `ClaudeClient` already follows this pattern — it's "Spring-ready"

### What a bean actually is
- **A regular Java object whose lifecycle is managed by the Spring container.**
- Spring instantiates it, injects dependencies, runs post-construct logic, keeps it in the ApplicationContext, disposes on shutdown
- Default scope is **singleton** — one instance shared across the app
- **Interview line:** *"A bean is a regular Java object whose lifecycle Spring manages — instantiation, dependency injection, post-construct logic, registration in the ApplicationContext, and shutdown. By default a singleton, so it must be thread-safe."*

### ApplicationContext
- Conceptually a `Map<String, Object>` — registry of all beans by name and type
- Built at startup from component scan + `@Configuration` classes
- When a class needs a dependency, Spring looks it up here

### How beans become beans — two ways
1. **Stereotype annotations** (`@Component`, `@Service`, `@Repository`, `@RestController`) on classes you write
2. **`@Bean` methods** in `@Configuration` classes — for classes you don't own (`HttpClient`, `RestTemplate`) or when you need custom construction logic
- **Interview line:** *"`@Component` and `@Bean` solve the same problem from different angles. `@Component` is for classes I write — annotate the class. `@Bean` is for classes I don't own — write a factory method that returns the configured instance."*

### Stereotype distinctions
- All three are functionally `@Component` — Spring detects them as beans
- `@Repository` adds **real behavior**: enables exception translation, wrapping persistence exceptions (`SQLException`, `HibernateException`) into Spring's `DataAccessException` hierarchy
- `@Service` and `@Component` are semantically different, functionally the same — labels for readability and intent
- **Interview line:** *"All three are stereotype annotations that make Spring detect the class as a bean. Functionally they're nearly identical, but `@Repository` adds one real behavior — exception translation into Spring's `DataAccessException` hierarchy. The others are mostly semantic."*

### Startup sequence (concrete)
1. `main()` runs `SpringApplication.run(...)`
2. Spring creates the ApplicationContext
3. Component scan finds `@Component`/`@Service`/etc.
4. Spring inspects constructors → builds a dependency graph
5. Instantiates beans in **topological order** (leaves first)
6. Injects dependencies as it instantiates
7. `@PostConstruct` callbacks fire
8. Boot applies auto-configuration based on classpath
9. App is "ready"
- **Why circular dependencies fail:** no valid topological order

### Bean scopes
- **Singleton** is the default — **for performance, not for safety**
- Thread safety is a **consequence/requirement** of being a singleton, not the reason for it
- ❌ Wrong framing: "singleton so it's thread-safe"
- ✅ Correct framing: "singleton for performance — and therefore must be thread-safe"
- **Interview line:** *"Spring beans are singletons by default for performance — creating a new instance on every injection would be wasteful for stateless services. The implication is that state in a bean is shared across all threads using it, so beans must be stateless or carefully synchronized. This is why immutability and constructor injection matter so much."*

### Spring vs Spring Boot
- **Spring** = the underlying framework (IoC, DI, AOP, MVC, etc.)
- **Spring Boot** = Spring + three things:
  1. Starter dependencies (curated bundles)
  2. Auto-configuration (conditional bean registration based on classpath)
  3. Embedded server (Tomcat) — runnable as a fat jar
- Boot doesn't replace Spring — it makes the common path frictionless
- **Interview line:** *"Spring is the underlying framework providing IoC, DI, AOP, MVC. Spring Boot adds starter dependencies, auto-configuration that registers default beans based on what's on the classpath, and embedded Tomcat for self-contained jars. Under the hood it's still Spring — Boot just removes the boilerplate."*

### Auto-configuration is not magic
- It's just **conditional bean registration**
- Spring Boot ships hundreds of `@Configuration` classes using `@ConditionalOnClass`, `@ConditionalOnMissingBean`, etc.
- They say: "if X is on the classpath and you haven't defined Y yourself, here's a default Y"
- Run with `--debug` to see the auto-config report

### `@SpringBootApplication` is three annotations
- `@Configuration` — class is a bean definition source (can contain `@Bean` methods)
- `@EnableAutoConfiguration` — turns on conditional bean registration
- `@ComponentScan` — scans the current package and subpackages for `@Component` etc.
- **Practical implication:** the package where `@SpringBootApplication` lives matters — component scanning only finds beans in that package and below. A `@Service` in a sibling package is invisible by default.

### `@Controller` vs `@RestController`
- Spring MVC supports two patterns: **server-side HTML rendering** (original) and **REST APIs returning JSON** (newer)
- **`@Controller`** without `@ResponseBody`: method returns a **view name** (a String), Spring's view resolver finds the template, renders HTML, sends it back
- **`@Controller` with `@ResponseBody`** on a method: that method's return value is serialized directly to the response body (typically JSON)
- **`@RestController`** = `@Controller` + `@ResponseBody` applied to every method automatically
- Every HTTP response has a body. The annotation determines **who fills it** — view template engine or serialization
- For my fantasy app: `@RestController` everywhere
- **Interview line:** *"`@RestController` is a meta-annotation combining `@Controller` and `@ResponseBody`. The distinction matters because `@Controller` originally returned view names that Spring resolved to HTML templates — a holdover from server-side rendered apps. `@ResponseBody` overrides that, telling Spring to serialize the return value directly to the response body. `@RestController` applies that to every method, which is what you almost always want for a REST API."*

### Common annotations reference

| Annotation | Purpose |
|------------|---------|
| `@SpringBootApplication` | Combines `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` |
| `@Component` | Generic "make this a bean" |
| `@Service` | Business logic bean (semantically labeled) |
| `@Repository` | Data access bean + exception translation |
| `@RestController` | `@Controller` + `@ResponseBody` (REST APIs) |
| `@Controller` | Web controller (returns view names by default) |
| `@Configuration` | Class containing `@Bean` methods |
| `@Bean` | Method producing a bean (factory pattern) |
| `@Autowired` | Inject this dependency (mostly optional with single constructor) |
| `@Value("${prop}")` | Inject a single config property |
| `@ConfigurationProperties` | Bind a group of properties to a class |

### Open Spring topics I haven't fully consolidated yet (revisit in interviews / later detours)
- JPA / Hibernate — relationships done (Detour 3), still need: JPQL deep dive, criteria queries, second-level cache
- `@Transactional` and how AOP proxies work — basics learned in Phase 1, need deeper dive (propagation, isolation levels)
- Spring Security
- Profiles (`@Profile`, `application-{profile}.properties`)
- `@Value` vs `@ConfigurationProperties` tradeoffs

---

## 🎯 JPA Relationships Deep Dive (Detour 3)

Done during Phase 1 Week 3 Day 3 when adding `player_stats` table with FK to `player`.

### Owning side vs inverse side
- In a database, relationships exist one way: child table has a FK to parent. `player_stats.player_id` references `player.id`.
- The entity with the FK column is the **owning side**. `PlayerStats` owns the relationship.
- `@ManyToOne` on `PlayerStats.player` with `@JoinColumn(name = "player_id")` maps the FK.
- `Player` *could* have `@OneToMany(mappedBy = "player") List<PlayerStats>` (inverse side), but I intentionally left it off — query stats through the repository when needed, not through entity navigation. Avoids accidental lazy loading.
- **Interview line:** *"The owning side of a JPA relationship is the entity whose table has the foreign key column. `@JoinColumn` maps the FK. The inverse side uses `mappedBy` to point back — it's optional and doesn't add a database column. I prefer querying through repositories over entity navigation to keep loading behavior explicit."*

### Lazy vs Eager fetching
- `FetchType.EAGER` (default for `@ManyToOne`): always JOINs to load the related entity. Almost always wrong for production.
- `FetchType.LAZY`: loads only when `getPlayer()` is called. Uses a Hibernate proxy placeholder until then.
- Always set `@ManyToOne(fetch = FetchType.LAZY)` explicitly. The default is a historical spec mistake.
- **Interview line:** *"`@ManyToOne` defaults to EAGER loading — a historical mistake in the JPA spec. I always set LAZY explicitly so related entities are loaded only when accessed. Eager loading at the mapping level hides expensive JOINs and causes N+1 problems."*

### The N+1 problem
- Query 100 `PlayerStats` → 1 query. Iterate and call `stats.getPlayer().getName()` on each → 100 more queries (one per lazy proxy). Total: 101 queries for what should be 1 JOIN.
- **Fix 1:** `JOIN FETCH` in JPQL: `SELECT s FROM PlayerStats s JOIN FETCH s.player WHERE s.year = :year`
- **Fix 2:** `@EntityGraph` on repository method — declarative, same effect.
- Key: know upfront which queries will need related data and fetch eagerly at the **query level**, not the **mapping level**.
- **Interview line:** *"The N+1 problem happens when lazy-loaded relationships trigger individual queries inside a loop. The fix is `JOIN FETCH` in JPQL or `@EntityGraph` on the repository method — both force a single query with a JOIN instead of N+1 round-trips. The key is knowing upfront which queries will need related data and fetching eagerly at the query level, not the mapping level."*

### Composite primary key with `@IdClass`
- `PlayerStatsId` holds `String playerId` + `int year` — the raw key values, not entity references
- Must implement `Serializable`, have a no-arg constructor, and override `equals()` + `hashCode()`
- Field names in `@IdClass` must match `@Id` field names in the entity exactly
- `Persistable<PlayerStatsId>` — `getId()` returns `new PlayerStatsId(playerId, year)`
- `JpaRepository<PlayerStats, PlayerStatsId>` — the ID type is the composite class
- **Interview line:** *"With `@IdClass`, the ID class fields must match the entity's `@Id` fields by name and type. The class must be `Serializable` with proper `equals` and `hashCode` — JPA uses these for identity checks and cache lookups. The repository uses the ID class as its second generic type."*

### Two-field pattern for FK columns
- `String playerId` (`@Id`, `@Column`) — the writable field. Used during ETL: `stats.setPlayerId("4046")` without loading the Player entity.
- `Player player` (`@ManyToOne`, `@JoinColumn`, `@JsonIgnore`) — the readable field. Used for Java navigation when needed.
- Both map to the same `player_id` column. `insertable = false, updatable = false` on the `@ManyToOne` prevents conflicts — tells JPA "the `playerId` String owns writes to this column."
- **Interview line:** *"I separate the raw FK column (`String playerId`) from the `@ManyToOne` relationship (`Player player`) on the same column. The raw field handles writes during ETL without loading related entities. The relationship handles reads with lazy navigation. `insertable = false, updatable = false` on the relationship prevents the two mappings from conflicting."*

### `@JsonIgnoreProperties(ignoreUnknown = true)` — class-level defensive parsing
- External APIs return many fields we don't need. Annotating the DTO record ensures unknown fields are silently ignored.
- Prevents breakage when the API adds new fields.
- Can also be set globally via `ObjectMapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)` — class-level makes intent explicit per DTO.
- **Interview line:** *"`@JsonIgnoreProperties(ignoreUnknown = true)` at the class level is defensive parsing for external API DTOs. External APIs can add fields at any time — if my deserialization fails on unknown fields, a Sleeper API change breaks my app. I take what I need and ignore the rest."*

---

## Week 2 — Spring AI Project

### Day 1 — Project Setup

**What I did**
- Generated a new Spring Boot project via Spring Initializr
- Imported into IntelliJ, verified Maven Wrapper, walked through every generated file

**Choices and why**
- Maven over Gradle — matches my work experience and Spring AI docs
- Spring Boot **3.5.14** (latest non-SNAPSHOT 3.x) over 4.0.x — 3.x stays on Java 17 (no forced JDK upgrade), maximum tutorial/StackOverflow compatibility, most enterprises run 3.x
- Java 17 stays the baseline
- Dependencies: Spring Web, Spring Boot Actuator, Anthropic Claude (Spring AI starter)
- Packaging Jar, Configuration Properties (over YAML — simpler for now)

**Initializr-generated structure — what each piece does**
- `pom.xml` — Maven build file, dependencies, plugins
- `src/main/java` — application code
- `src/main/resources` — config (`application.properties`), `static/`, `templates/` (unused for REST APIs)
- `src/test/java` — tests; one-way visibility (test sees main, not vice versa)
- `mvnw` / `mvnw.cmd` / `.mvn/` — Maven Wrapper; pins Maven version per-project
- `.gitignore`, `.gitattributes` — defaults, fine to leave

**Maven Wrapper — two version concepts**
- `distributionUrl` → which Maven the project uses (e.g. 3.9.11)
- `wrapperVersion` → which wrapper scripts version is in use (e.g. 3.3.4)
- They version independently because they evolve at different rates
- Multiple Maven versions coexist in `~/.m2/wrapper/dists/`, one folder per project's needs
- **Interview line:** *"Maven Wrapper has two independent versions — the build tool itself and the wrapper scripts that download it. They evolve separately. The benefit is project-level reproducibility: anyone cloning gets the exact Maven version validated against, and multiple projects can coexist with different Maven versions on the same machine."*

**IntelliJ setup**
- Settings → Build Tools → Maven → **Use Maven wrapper** (matches CLI behavior)
- IDE build, CLI build, CI build all use the same Maven version

### `pom.xml` deep dive

**Parent POM (`spring-boot-starter-parent`)**
- Pins versions of hundreds of libraries that work together
- Solves "dependency hell" — don't ask if Jackson X works with Spring Y, Boot's team already validated
- Configures plugins with sensible defaults, sets Java compilation version

**Maven coordinates: `groupId:artifactId:version`**
- `0.0.1-SNAPSHOT` = mutable development version; Maven re-downloads periodically
- Non-SNAPSHOT = release, cached permanently
- Convention: develop in SNAPSHOT, strip suffix when releasing

**Dependencies without explicit versions**
- Versions come from parent POM or imported BOM
- Spring AI ships its own BOM because it releases independently of Spring Boot
- `<dependencyManagement>` with `<scope>import</scope>` brings in version declarations only

**Maven scopes**
| Scope | In main? | Packaged? | In tests? |
|---|---|---|---|
| `compile` (default) | ✅ | ✅ | ✅ |
| `provided` | ✅ | ❌ | ✅ |
| `runtime` | ❌ | ✅ | ✅ |
| `test` | ❌ | ❌ | ✅ |

`<scope>test</scope>` keeps JUnit/Mockito out of the production jar.

**`spring-boot-maven-plugin`**
- Adds goals like `spring-boot:run`, `spring-boot:build-image`
- Packages a **fat jar** (uber jar) — code + all deps + embedded launcher in one file
- `java -jar app.jar` runs the full web app, no separate Tomcat install
- Foundation of Boot's deployment story (works seamlessly in Docker)

### Day 2 — First Spring AI Call

**What I did**
- Configured Spring AI in `application.properties`:
  - `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY}` — resolved from env var
  - Model, max-tokens, temperature defaults
- Created `ChatRunner` implementing `CommandLineRunner`
- Made my first ChatClient call: ~20 lines vs ~80 in Week 1 hand-rolled version

**The fail-fast moment I saw**
- Before configuring the API key, `./mvnw test` failed with `BeanCreationException`
- Spring AI's auto-config saw the Anthropic starter on classpath → tried to create `AnthropicApi` bean → no key → context startup failed
- This is **good** behavior: fail at startup, not at the first user request
- Confirms auto-configuration is wired in correctly

**Property placeholder resolution (`${PROP}`)**
- Spring resolves placeholders from a hierarchy: CLI args > system props > env vars > properties file > defaults
- Same `application.properties` works across dev/test/prod — each env overrides via env vars
- Foundation of 12-factor app config
- Can specify defaults: `${PROP:default-value}`
- **Interview line:** *"Spring resolves `${PROP}` placeholders from a layered hierarchy — command line args, system properties, env vars, properties files. The same `application.properties` works across environments because each environment overrides what it needs without code changes."*

**API key storage decision**
- Set `ANTHROPIC_API_KEY` as a system-level env var on local machine for convenience
- Tradeoff: simpler dev workflow vs slightly higher exposure on a personal machine
- Real production: secret managers (AWS Secrets Manager, Vault), CI/CD secrets, container env injection — never on disk
- **Interview line:** *"For local development I use a system env var. For production, secrets go in a secret manager — AWS Secrets Manager, Vault, or container-level env injection from a CI/CD pipeline. The application reads `${ANTHROPIC_API_KEY}` regardless of source — that's the value of Spring's property placeholder system; the same code works across environments."*

### `CommandLineRunner` — what it is and when it runs
- Spring Boot interface for code that should execute once after context is built
- Sequence: `main()` → context built → Tomcat starts → all `CommandLineRunner` beans' `run()` methods called → `SpringApplication.run()` returns
- Real uses: data seeding, smoke tests, CLI tools wrapping Spring context
- **Interview line:** *"`CommandLineRunner` is a Spring Boot hook for executing code once the application context is fully initialized. It runs after all beans are created and the embedded server has started, but before `SpringApplication.run()` returns control. Common uses include data seeding, one-time migrations, or CLI tools that wrap a full Spring context."*

### `ChatClient` and the Builder injection pattern

**Why inject `ChatClient.Builder`, not `ChatClient` directly**
- `ChatClient` is meant to be customized per-use-case — different system prompts, temperatures, retry policies for different beans
- Spring AI exposes the Builder as a bean; each consumer builds its own configured `ChatClient`
- Multiple ChatClients can share the underlying API client but have different defaults
- **Interview line:** *"Spring AI exposes `ChatClient.Builder` rather than `ChatClient` directly because the chat client is meant to be customized per-consumer. Each bean that needs one builds its own configured instance — default system prompt, temperature, retry policy — from the shared Builder. It's a common pattern when a framework wants to support multiple specialized configurations of the same underlying client."*

**The fluent chain**
```java
chatClient.prompt()
    .user("...")
    .call()       // ← HTTP call to Anthropic happens here, synchronous
    .content();   // ← extracts text from the response object
```
- `.call()` returns a `ChatResponse` with full metadata
- `.content()` is a shortcut for "just the text"
- For token usage: `.call().chatResponse().getMetadata().getUsage()`

### Comparison: Week 1 vs Week 2

| Concern | Week 1 (claude-playground) | Week 2 (spring-ai-playground) |
|---|---|---|
| Lines of code | ~80 to call Claude | ~20 |
| HttpClient setup | Manual | Auto-configured |
| JSON building | Manual with Jackson | Hidden |
| API key reading | `System.getenv()` | `${ANTHROPIC_API_KEY}` resolved by Spring |
| Error handling | Custom `ClaudeApiException` | Spring's exception hierarchy |
| Token tracking | Manual `System.out.println` | Available on response object |
| Retries | None | Built-in, configurable |
| How to run | `EnvTest.main()` | `CommandLineRunner` after context init |

**Underlying API call is identical.** Difference is abstraction over the boring 80%.

### The hallucination I saw firsthand — most important lesson today

Asked Claude for "Top 5 NFL Fantasy Football QBs for 2024." Got confident, plausible rankings (Mahomes, Allen, Goff, Jackson, Burrow).

**Claude has no real 2024 stats.** It pattern-matched from training data. The rankings are plausible but not based on actual fantasy points.

Claude even added a disclaimer: *"Fantasy rankings can shift based on preseason performance, injuries, and trades."*

**This is exactly the failure mode RAG and tool-calling solve.** When my draft assistant launches in Phase 4, I don't want Claude inventing rankings from training data — I want it reasoning over real data from my PostgreSQL database. That's RAG. That's tool calling.

- **Interview line:** *"A pure LLM call without grounding will hallucinate confident but unverified answers — especially on data-heavy questions like rankings, stats, or current events. The mitigation is RAG: retrieve real data from your system first, inject it into the prompt, then let the LLM reason over verified facts. Spring AI supports this natively via `VectorStore` and tool-calling APIs."*

### Day 3 — REST Endpoint + SLF4J Logging
**Time:** ~1h

**What I did**
- Removed `ChatRunner` (served its purpose, git preserves it)
- Created `AskController` — `@RestController` with `POST /ask` endpoint
- Added SLF4J logging (replacing `System.out.println`)
- Tested with curl (happy path + missing body error case)

**The endpoint**
```java
@RestController
public class AskController {
    private static final Logger log = LoggerFactory.getLogger(AskController.class);
    private final ChatClient chatClient;

    public AskController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @PostMapping("/ask")
    public String ask(@RequestBody String question) {
        log.info("Received question: {}", question);
        String response = chatClient.prompt().user(question).call().content();
        log.info("Response length: {} chars", response.length());
        return response;
    }
}
```

### Spring MVC Request Lifecycle (Detour 2)

**The full flow of a request through Spring Boot:**
1. **Tomcat** receives TCP connection, parses HTTP, wraps in `HttpServletRequest`/`HttpServletResponse`
2. **DispatcherServlet** — the single front controller for ALL HTTP requests
3. **HandlerMapping** — matches URL + method to a controller method (`POST /ask` → `AskController.ask()`)
4. **HandlerAdapter** — figures out how to invoke the method, reads `@RequestBody` etc.
5. **HttpMessageConverter** — translates HTTP body ↔ Java objects (Jackson for JSON, string converter for plain text)
6. **Your method runs** — business logic
7. **HttpMessageConverter** — serializes return value to response body
8. **Response sent** back through Tomcat to client

Everything above and below "your method runs" is framework. You write the middle.

**Key concepts:**
- **DispatcherServlet** = front controller pattern. Every request passes through it.
- **HandlerMapping** = URL routing. Matches `@PostMapping`, `@GetMapping`, etc. to methods.
- **HttpMessageConverter** = the translation layer between HTTP bytes and Java types. `MappingJackson2HttpMessageConverter` handles JSON, auto-configured when Jackson is on the classpath.
- **`@RequestBody`** = "deserialize the HTTP body into this parameter." Required by default — missing body → 400 before your method runs.
- **`@RequestParam`** = reads URL query parameters (`/ask?question=...`). Different source than `@RequestBody`.
- **Interview line:** *"DispatcherServlet is Spring MVC's front controller. Every HTTP request passes through it. It delegates to HandlerMappings to find the right controller method, invokes it via HandlerAdapter, then uses HttpMessageConverters to produce the response. It's the central orchestration point."*

### SLF4J Logging — Why And How

**Two layers:**
- **SLF4J** = logging API (facade) — defines how you log
- **Logback** = logging implementation — actually writes the output. Spring Boot auto-configures it.

Why two layers: same pattern as Spring AI abstracting over LLM providers. Code against the API, swap implementations without code changes.

**The standard pattern:**
```java
private static final Logger log = LoggerFactory.getLogger(AskController.class);
```
- `static` — one logger per class, loggers are stateless
- `final` — never reassigned
- `AskController.class` — logger named after the class, so log output identifies the source

**`{}` placeholder syntax:**
```java
log.info("Received question: {}", question);
```
- Better than string concatenation (`"Received: " + question`)
- If the log level is disabled, the string is **never constructed** — saves CPU in high-throughput systems
- Handles null safely

**Why `System.out.println` is a red flag in production:**
| `System.out.println` | SLF4J/Logback |
|---|---|
| No timestamp | Timestamp included |
| No log level | INFO, WARN, ERROR, DEBUG |
| No class identification | Logger name shown |
| Can't disable selectively | Configurable per-package |
| Can't route to files | Console, file, remote — all configurable |
| Always constructs the string | `{}` avoids unnecessary work |

### Stateless vs Stateful — Core Concept

**Stateless:** no mutable internal data. Every call is independent. Inherently thread-safe.
- Examples: Logger, Controller, Service, `ObjectMapper`, `HttpClient`

**Stateful:** accumulates or modifies data between calls. Concurrent access requires synchronization.
- Examples: Shopping cart, HTTP session, `StringBuilder`, `Iterator`, database connection

**Why it matters for Spring:**
- Beans are singletons by default → shared across all requests/threads
- Singleton + stateless = safe
- Singleton + stateful = dangerous (concurrent modification, data corruption)
- Rule: **Spring beans should be stateless.** State lives in the database, session, or cache — not in bean fields.
- **Interview line:** *"A stateless object has no mutable internal data — every call is independent, making it inherently thread-safe. Spring beans are singletons by default, so they should be stateless. If a bean must hold state, synchronize access, use thread-safe data structures, or change the bean scope. Application state belongs in the database or session, not in bean fields."*

### Spring's Default Error Handling — What I Observed

Sent `POST /ask` with no body:
- Spring threw `HttpMessageNotReadableException` **before my method ran**
- `DefaultHandlerExceptionResolver` caught it → returned HTTP 400
- Response: `{"timestamp":"...","status":400,"error":"Bad Request","path":"/ask"}`
- My `log.info("Received question...")` line never executed — framework short-circuited

**Comparison to Week 1:**
| Week 1 | Week 2 |
|---|---|
| Empty input reached Anthropic API → 400 from API | Empty input caught by Spring → 400 before method runs |
| Manual validation needed | Framework enforces `@RequestBody` automatically |
| Raw API error JSON | Structured Spring Boot error JSON |

- `@RequestBody` is required by default — missing body = instant 400
- Can make optional with `@RequestBody(required = false)`
- For custom error messages: `@ControllerAdvice` (centralized exception handler) — will add later
- **Interview line:** *"`@RequestBody` is required by default. If the body is missing, Spring throws `HttpMessageNotReadableException` before the controller method executes. This is fail-fast at the framework level — invalid requests never reach business logic."*

### Day 4 — Structured Output
**Time:** ~0.5h

**What I did**
- Created `PlayerRanking` Java record (name, team, position, rank)
- Added `POST /rank` endpoint returning `List<PlayerRanking>` using `.entity()`
- Tested with valid prompts and intentionally mismatched prompts

**Java records (Java 16+)**
- Immutable data carriers — compiler generates constructor, accessors, `equals`/`hashCode`/`toString`
- Accessor methods use `name()` not `getName()` — no `get` prefix
- Perfect for DTOs, API responses, value objects
- Jackson works with records out of the box
- 1 line replaces ~30 lines of traditional class boilerplate
- **Interview line:** *"Java records are immutable data carriers introduced in Java 16. The compiler generates the constructor, accessors, equals, hashCode, and toString. They're ideal for DTOs and value objects — anywhere you need a class that just holds data without behavior."*

**`.content()` vs `.entity()` — the key distinction**
```java
.call().content();     // returns raw String — free-form text
.call().entity(Type);  // returns parsed Java object — structured data
```
- `.entity()` tells Spring AI to instruct Claude to respond in JSON matching the Java type's schema
- Spring AI automatically injects JSON format instructions into the prompt
- Jackson deserializes the response into typed Java objects
- For generic types (like `List<T>`), must use `ParameterizedTypeReference` due to Java type erasure

**`ParameterizedTypeReference` — Java type erasure workaround**
```java
// Single object — simple
.entity(PlayerRanking.class)

// Generic type — need ParameterizedTypeReference
.entity(new ParameterizedTypeReference<List<PlayerRanking>>() {})
```
- Java erases generic types at runtime (`List<PlayerRanking>.class` doesn't exist)
- `ParameterizedTypeReference` preserves generic type info via anonymous subclass
- Same pattern used in `RestTemplate`, `WebClient`, Spring AI
- Not daily use, but no alternative when you need generic type deserialization
- **Interview line:** *"`ParameterizedTypeReference` is Spring's workaround for Java type erasure. At runtime, `List<PlayerRanking>.class` doesn't exist — generics are erased. By creating an anonymous subclass, the generic type information is captured and available at runtime. You see this pattern anywhere Spring deserializes generic types."*

**The "joke" experiment — structured hallucination**
- Sent "Tell me a joke" to the `/rank` endpoint expecting `List<PlayerRanking>`
- Claude returned valid JSON: `{"name":"Why don't scientists trust atoms","team":"Classic Jokes","position":"Setup","rank":1}`
- Parsed successfully into Java objects. Code logged "Received 2 player rankings."
- **The pipeline worked perfectly. The data was nonsense.**
- This is harder to catch than free-text hallucination — valid types, valid JSON, meaningless content
- **Key lesson:** Structured output solves the parsing problem, not the accuracy problem. Must combine with RAG/tool calling.
- **Interview line:** *"Structured output guarantees the format is correct — valid JSON, matching your schema. But it doesn't guarantee the content is correct. An LLM can return perfectly typed Java objects with completely fabricated data. That's why structured output must be combined with RAG or tool calling — the LLM reasons over real data, then formats its answer into your schema. Structure without grounding is just well-formatted hallucination."*

**Spring AI auto-retry — saw it in action**
- One request failed with `Connection reset` (network hiccup)
- Spring AI automatically retried: `WARN ... Retry count: 1, Exception: Connection reset`
- Second attempt succeeded — user never saw the failure
- No retry logic written by me — auto-configured by Spring AI
- Configurable: max attempts, backoff, which exceptions trigger retry
- **Interview line:** *"Spring AI auto-configures retry with exponential backoff for transient failures. The retry is transparent to calling code. You can customize it via properties or a custom RetryTemplate bean."*

### Day 5 — System Prompts + Week 2 Wrap-up
**Time:** ~0.5h

**What I did**
- Added a default system prompt via `ChatClient.Builder.defaultSystem()`
- Tested the same queries with and without system prompt — observed different reasoning
- Tested honesty instruction with impossible questions (2025 season data)

**Three ways to set system prompts in Spring AI**
1. **Per-request (inline):** `.system("...")` on the prompt — for dynamic behavior
2. **Default on ChatClient (Builder):** `.defaultSystem("...")` — for consistent behavior across all calls
3. **From a file:** `@Value("classpath:prompts/expert.txt")` + `.defaultSystem(resource)` — for long prompts, independent versioning
- **Interview line:** *"System prompts can be set per-request, as a ChatClient default, or externalized to resource files. In production I'd externalize them — prompts change more frequently than code, and you want to tune them without redeploying."*

**What system prompts actually change**
- Not just tone — they change **reasoning patterns**
- Without prompt: Mahomes #1 (name recognition). With analyst prompt: Goff #1 (statistical performance)
- The system prompt defines the model's evaluation framework — what to prioritize, how to weigh evidence
- For my app: system prompt = analyst's philosophy; user tactics = layer on top

**The honesty test — most important result**
- Asked about 2025 in-season stats (impossible for Claude to know)
- System prompt included: "when you don't have real data, say so honestly"
- Claude responded: *"I don't have access to 2025 NFL season data"*
- Without that instruction, Claude would have hallucinated plausible stats
- Claude even suggested the right solution: "Analyzing QB performance *if you share the stats*"
- That suggestion IS my app's architecture: I share the stats (from PostgreSQL), Claude analyzes them

**Java text blocks (Java 15+)**
```java
String prompt = """
    You are a senior NFL fantasy football analyst.
    When ranking players:
    - Base rankings on actual statistical performance
    """;
```
- Multi-line string literals — no `\n` concatenation
- Leading indentation stripped automatically
- Very useful for prompts, SQL, JSON templates

### Week 2 — Summary

**What exists now:**
```
spring-ai-playground/
├── SpringAiPlaygroundApplication.java    ← Boot entry point
├── AskController.java                    ← two endpoints: /ask (text) and /rank (structured)
├── PlayerRanking.java                    ← Java record (immutable DTO)
├── application.properties                ← API key, model, max-tokens, temperature
└── pom.xml                               ← Spring Boot 3.5.14 + Spring AI 1.1.6
```

**Capabilities:**
| What | How |
|---|---|
| Call Claude from REST API | `POST /ask` |
| Get structured Java objects | `POST /rank` with `.entity()` |
| Control Claude's behavior | System prompt via `defaultSystem()` |
| Handle errors gracefully | `@RequestBody` validation, auto-retry |
| Log properly | SLF4J with `{}` placeholders |
| Configure externally | `application.properties` with `${ENV_VAR}` |

**Week 1 vs Week 2:**
| Concern | Week 1 (hand-rolled) | Week 2 (Spring AI) |
|---|---|---|
| Lines of code | ~80 | ~20 |
| HTTP plumbing | Manual | Auto-configured |
| JSON building/parsing | Manual Jackson | Hidden |
| Error handling | Custom exception class | Framework handles it |
| Retries | None | Built-in, automatic |
| Structured output | Not possible | `.entity()` with type mapping |
| System prompts | Raw JSON field | Builder pattern, externalizable |

**Phase 0 is complete.** Foundation laid for the real project.

---

## Things I Could Talk About In An Interview Right Now

**Anthropic API / LLM fundamentals**
- How an LLM API call works at the HTTP level (endpoint, headers, body, response)
- Why you don't hand-craft JSON (use a library like Jackson)
- Why you reuse `HttpClient` instead of creating per-call
- Why constructor-init + final fields = thread-safe immutable client
- Why wrap low-level checked exceptions in domain-specific RuntimeExceptions
- Why `Thread.currentThread().interrupt()` matters when catching `InterruptedException`
- Difference between system prompt and user message
- Why we feed Claude data from our own database instead of letting it fetch
- Token costs and why `max_tokens` is a real production concern
- Why we use Haiku for learning, Sonnet for production
- Why HTTP 200 doesn't mean a successful LLM response — `stop_reason` matters
- The "context bomb" problem: LLM APIs trust callers completely; client must validate input size
- Two kinds of timeout: connection vs request
- Failure modes seen first-hand: 401 (auth), 404 (bad model), 400 (empty input), IOException (network), truncation (max_tokens), unchecked massive input

**Spring / Spring Boot**
- What IoC actually means and what problem it solves
- What a bean is at runtime
- Why constructor injection is preferred (4 reasons)
- Singleton scope is the default for performance, not safety — and the implication
- The three annotations bundled inside `@SpringBootApplication`
- `@Component` vs `@Bean` — when to use each
- Why `@Repository` is more than just a label (exception translation)
- `@Controller` vs `@RestController` and why `@RestController` exists
- Auto-configuration is conditional bean registration, not magic
- Why component scan package placement matters
- Maven Wrapper: why projects ship `mvnw` and what problem it solves
- Why `spring-boot-starter-parent` exists (dependency hell avoidance)
- What a Maven BOM is and why Spring AI ships its own
- Maven scopes (`compile`/`provided`/`runtime`/`test`) and what each means
- The fat jar: what `spring-boot-maven-plugin` produces and why it matters for deployment
- Property placeholder resolution (`${PROP}`) and the layered config hierarchy
- API key handling: dev env vars vs production secret managers
- `CommandLineRunner` — what it is and when it fires in the startup lifecycle
- `@Transactional` self-call proxy bypass: internal calls via `this` skip the AOP proxy — annotation is invisible
- Why the fix for independent transactions is extracting to a separate bean (each gets its own proxy)
- `ResponseEntity` for explicit HTTP response control vs returning objects directly

**Spring AI**
- Spring AI is an abstraction over multiple LLM providers (Anthropic, OpenAI, Mistral, Ollama)
- Auto-configuration creates the `ChatClient` for you when a starter is on the classpath
- The `BeanCreationException` on missing API key is fail-fast at startup — preferred over runtime failures
- Why we inject `ChatClient.Builder` instead of `ChatClient` (per-consumer customization)
- The fluent prompt API: `.prompt().user(...).call().content()` and what each call does
- LLMs hallucinate confidently on data-heavy questions; RAG is the mitigation
- Comparing hand-rolled (Week 1) vs Spring AI (Week 2): same Claude, ~75% less code, retries/timeouts/errors all built in

**Spring MVC / REST**
- The full request lifecycle: Tomcat → DispatcherServlet → HandlerMapping → HandlerAdapter → HttpMessageConverter → controller method → response
- DispatcherServlet as the front controller pattern
- `HttpMessageConverter` translates between HTTP bodies and Java objects (Jackson auto-configured)
- `@RequestBody` vs `@RequestParam` — different sources (body vs URL params)
- `@RequestBody` is required by default — missing body = 400 before method runs
- Spring Boot's default error handling via `DefaultHandlerExceptionResolver`
- SLF4J as a logging facade with Logback as the implementation
- Why `{}` placeholders are better than string concatenation in logging
- Stateless vs stateful — why Spring beans must be stateless (singleton + shared across threads)

**Structured Output / Prompt Engineering**
- `.content()` returns text; `.entity()` returns parsed Java objects — the difference between chatbot and application
- `ParameterizedTypeReference` as Java's type erasure workaround for generic deserialization
- Structured output solves parsing, not accuracy — well-formatted hallucination is still hallucination
- System prompts change reasoning patterns, not just tone — they define the evaluation framework
- Three ways to set system prompts in Spring AI (per-request, default on Builder, externalized to file)
- Instructing the LLM to admit uncertainty instead of hallucinating — critical production pattern
- Java records as immutable DTOs for LLM response mapping
- Java text blocks for multi-line prompts/SQL/JSON

**Data Layer / Infrastructure**
- Docker Compose for declarative, reproducible local infrastructure
- PostgreSQL + pgvector: relational DB and vector DB in one system
- Flyway for versioned schema migrations vs Hibernate `ddl-auto` (why `validate` not `update`)
- Spring Data JPA derived query methods (SQL generated from method names)
- Source system IDs as primary keys in ETL pipelines
- Why JPA entities can't be records (Hibernate needs mutability + no-arg constructor)
- Port conflict debugging on Windows (WSL, Docker, local services)
- Java package naming convention: reverse domain name (`readoption.app` → `app.readoption`)
- `Persistable` interface to eliminate SELECT-per-entity in bulk saves with manual IDs
- `@Transactional` for atomic batch operations — and the self-call proxy bypass gotcha
- `FAIL_ON_UNKNOWN_PROPERTIES = false` for defensive external API parsing
- `@JsonIgnore` to prevent internal fields leaking into API responses
- `@JsonProperty` for snake_case ↔ camelCase field mapping
- DTO vs Entity separation — external API shape vs internal database shape
- `Set.of()` throws NPE on `contains(null)` — explicit null checks before collection operations
- Optional: method return types only, not fields/DTOs/parameters
- Idempotent sync: first run INSERTs, subsequent runs UPDATE — upsert pattern with `Persistable`
- `@PrePersist`/`@PreUpdate` lifecycle callbacks vs database defaults for audit timestamps
- `@Query` with JPQL for custom queries beyond method naming (projections, aggregations)
- JPQL vs native SQL: entity/field names vs table/column names, portability tradeoffs
- Computed/derived tables: no FK needed when application guarantees integrity and data is fully recomputable
- Leftmost prefix rule: composite index on (A, B, C) covers queries on A, A+B, A+B+C — separate A index is redundant
- `show-sql=true` bypasses logging framework — production uses `logging.level.org.hibernate.SQL`

**JPA / Hibernate**
- `@ManyToOne` owning side — the entity whose table has the FK column
- `@OneToMany` inverse side with `mappedBy` — optional, doesn't add a DB column
- Why I skip the inverse side: query through repositories, not entity navigation
- FetchType.LAZY vs EAGER — why `@ManyToOne` defaults to EAGER is a spec mistake
- The N+1 problem: what it is, how to detect it (`show-sql=true`), how to fix it (`JOIN FETCH`, `@EntityGraph`)
- Composite primary keys with `@IdClass` — field names must match, `Serializable`, `equals`/`hashCode`
- `Persistable<CompositeIdType>` — `getId()` returns the composite ID object
- Two-field FK pattern: raw String for writes, `@ManyToOne` for reads, `insertable=false/updatable=false`
- `@JoinColumn` maps the FK column — JPA auto-joins to the related entity's PK
- Hibernate lazy proxies (ByteBuddy) — why serializing entities directly to JSON fails
- Entities vs DTOs for API responses — returning entities couples API shape to DB schema
- `@JsonIgnoreProperties(ignoreUnknown = true)` at class level for external API DTOs
- `TypeReference` (Jackson) for generic deserialization — same pattern as `ParameterizedTypeReference`
- Double → Integer type conversion in ETL — DTO types match source, entity types match database
- Hibernate physical naming strategy: `CamelCaseToUnderscoresNamingStrategy` (Spring Boot default)
- When `@Column` is needed vs when naming convention handles it
- Adding columns via Flyway migration + re-sync pattern for evolving ETL schemas

**Scoring / Business Logic Architecture**
- Five interconnected architectural decisions for fantasy scoring (formula location, result storage, format handling, computation timing, PPG)
- Why scoring formula lives in Java service, not SQL (business logic, testable, format-aware)
- Strategy pattern via enum: scoring format rules carried by enum values, no switch statements
- Why external projections over building own prediction engine — division of labor with the LLM
- Claude's role: strategic reasoning over structured data, not statistical prediction
- `BigDecimal` String constructor vs double constructor — exact decimal representation
- Entity/DTO separation strategy: new entities get DTOs from start, existing refactored when touched
- Computed scoring table with 3-column composite PK — no source discriminator (year is the discriminator)
- No FK on derived tables — application guarantees integrity, FK would break when projections land
- Scoring pipeline wired into stats sync: save stats → auto-compute all 6 formats → persist
- Recompute endpoint for formula iteration during development — same ScoringService, different trigger
- Denormalized games_played in scoring table — safe because computed alongside source data

**Interface Design / Testing**
- Extracted interface pattern: designing `StatLine` from existing `PlayerStats` getters
- Why interface over concrete class: `ScoringService` works on historical stats AND future projections without changes
- Anonymous interface implementations for zero-infrastructure unit testing
- `@DisplayName` for self-documenting test suites
- Primitive vs wrapper return types in interface implementation: `int` does not satisfy `Integer`

**Lombok**
- Lombok is compile-time only — generates bytecode during compilation, absent at runtime (marked `<optional>`)
- Safe on JPA entities: @Getter, @Setter, @NoArgsConstructor, @AllArgsConstructor, @Builder
- Dangerous on JPA entities: @Data (generates equals/hashCode on all fields, breaks Hibernate)
- @EqualsAndHashCode: safe on ID/value classes, dangerous on entities with lazy fields
- @Builder.Default preserves field initializers — without it, builder ignores `= true` and uses type default
- IntelliJ requires annotation processing enabled + Lombok plugin
- Static factory method vs @Builder: factory methods have names, support multiple creation paths; @Builder is for many-field construction
