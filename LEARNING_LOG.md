# AI Development Learning Log

A running log of what I learn, build, and get confused about while learning AI development with my Java/Spring background.

**Goal:** Career evolution into AI + Java backend roles. Build a fantasy football draft assistant as the portfolio project.

**Stack focus:** Java 21, Spring Boot 4, Spring AI 2.0, Anthropic Claude API, PostgreSQL/pgvector, Maven, IntelliJ, Claude Code.

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
| **Page vs Slice** | `Page` adds a COUNT query for total elements/pages; `Slice` over-fetches one row to know if a next page exists, no count. `Page` for "3 of 13", `Slice` for infinite scroll. |
| **PagedModel / VIA_DTO** | Spring Data 3.3 stable paged envelope. `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` wraps `PageImpl` so the JSON contract doesn't leak Spring internals. Returning `Page` raw warns since 3.1. |
| **JPQL constructor expression** | `SELECT new <FQN>(...)` projects rows straight into a DTO constructor — no entity hydration, no lazy proxies. Records are ideal targets. |
| **Hibernate 6 entity join** | `JOIN Other o ON o.id = e.fkId` between entities with no mapped association. Impossible pre-Hibernate-6 (native SQL only). Lets computed tables stay FK/association-free yet joinable in HQL. |
| **`@Enumerated(EnumType.STRING)`** | Persist an enum as its name (stable, readable). Never `ORDINAL` (stores declaration index → reordering corrupts rows). STRING maps to the existing varchar, so no migration when the column already held names. |
| **`@ResponseStatus`** | On a custom exception, maps it to an HTTP status automatically (e.g. NOT_FOUND → 404). Lightweight; graduate to `@ControllerAdvice` for structured error bodies. |
| **`@Transactional(readOnly = true)`** | Read-path optimization — Hibernate skips dirty-checking/flush, reads share one snapshot. Signals intent. |
| **Null-or-match filter** | `AND (:p IS NULL OR col = :p)` — one query handles filtered and unfiltered. Mirror in the count query (and any join it needs). Multiply filters → Criteria/Specifications. |
| **Searched CASE (HQL)** | `CASE WHEN :x = 'A' THEN col1 ... END` picks a column by parameter at query time (you can't bind a column name). Safer HQL form than simple `CASE :x WHEN`. |
| **Positional rank (derived)** | "QB8" = count of same-position players with a better ADP, +1. A function of (position, ADP) — computed on read, never stored. Single-player = count; everyone-at-once = `RANK() OVER (PARTITION BY ...)`. |
| **Null trap in count-ranking** | `col < :null` is *unknown*, so a null-value row counts 0 better → ranks #1. Guard at the service: no value ⇒ null rank. |
| **Test slice** | A focused Boot test context loading only one layer (`@WebMvcTest`, `@DataJpaTest`, `@JsonTest`…) instead of the whole app — faster, failures point at one layer. |
| **`@WebMvcTest`** | Slice loading only the web layer for given controllers (MVC infra, Jackson) — no services/repos/DB. Collaborators supplied as `@MockitoBean`. |
| **`@DataJpaTest`** | Slice loading only JPA (entities, repositories, datasource). Transactional + rolled back per test. Defaults to in-memory H2 — disable with `@AutoConfigureTestDatabase(replace = NONE)`. |
| **`@MockitoBean`** | Replaces a context bean with a Mockito mock. Spring Framework 6.2 replacement for the deprecated (Boot 3.4) `@MockBean`. |
| **`MockMvc`** | Drives the real DispatcherServlet/binding/Jackson without a running server; assert status, headers, JSON (`jsonPath`). |
| **`ArgumentCaptor`** | Captures the argument a mock was actually called with, to assert on what crossed a boundary (e.g. the clamped `Pageable`). Behavior, not state. |
| **`verify(mock, never())`** | Asserts an interaction did *not* happen — proves a guard short-circuited before calling a collaborator. |
| **Testcontainers** | Spins real services (Postgres) in Docker for tests. `@ServiceConnection` auto-wires Spring's datasource to the container. |
| **`@ServiceConnection`** | Boot 3.1+ — reads a container's host/port/credentials and configures the matching Spring connection automatically; replaces `@DynamicPropertySource` boilerplate. |
| **Singleton container** | A `static` container started once for the whole test run (vs `@Container` per-class), reused by every test via a shared base class. |
| **`@AutoConfigureTestDatabase(replace = NONE)`** | Stops `@DataJpaTest` swapping the datasource for embedded H2, so the slice uses the real (Testcontainers) database. |
| **Strict stubs (Mockito)** | `MockitoExtension` default: an unused stub fails the test; forces each test to stub only its real call path. `lenient()` opts a stub out. |
| **Fixture factory** | One place that builds valid test entities satisfying all constraints; tests bypass ingestion so they'd otherwise each re-discover NOT NULL fields. |
| **`@RestControllerAdvice`** | `@ControllerAdvice` + `@ResponseBody`. One class of `@ExceptionHandler` methods applied across all controllers; Spring picks the most specific handler for the thrown type. |
| **`ProblemDetail` (RFC 9457)** | Spring 6 standardized error body — `type`/`title`/`status`/`detail`/`instance` + open properties, served as `application/problem+json`. Returning it directly sets status + content type. |
| **`ResponseEntityExceptionHandler`** | Base class with built-in `@ExceptionHandler`s for the Spring MVC exception family (rendered as ProblemDetail in Boot 3). Extend it to inherit them and prevent a catch-all masking a framework 4xx into 500. |
| **`@Validated` vs `@Valid`** | `@Validated` (Spring, on class) enables method-parameter validation via proxy; `@Valid` (Jakarta, on a `@RequestBody`) cascade-validates fields. |
| **`MethodArgumentNotValidException`** | Thrown by `@Valid` on a request body; a Spring MVC exception → 400 handled by the base class. |
| **`ConstraintViolationException`** | Thrown by `@Min`/`@Max` on method params under `@Validated`; NOT a Spring MVC exception → 500 unless explicitly handled. |
| **`spring-boot-starter-validation`** | Separate dependency since Boot 2.3; without it Bean Validation annotations are silently inert. |
| **Projection interface (Spring Data)** | Interface of getters whose names match query column aliases; Spring generates the implementing proxy. The native-query analogue of a JPQL constructor expression (bound by name, not constructor position). |
| **Native query (`nativeQuery = true`)** | Raw SQL against the DB — table/column names, no JPQL grammar. Required for features JPQL can't express (window functions). No constructor expression available. |

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
| **TE premium** | Scoring variant awarding tight ends extra points per reception (e.g., 1.5 vs 1.0). Position-dependent rule — can't be expressed by a format that only varies reception value and passing-TD value. Needs a parameterized ScoringRules + player position. |
| **ADP sentinel (999)** | Sleeper's "unranked" marker in ADP fields. Converted to NULL in the ETL so sorting doesn't bury undrafted players, and to stay inside `NUMERIC(5,2)`. |
| **Projection provider vs aggregator** | Sleeper aggregates projections from providers (the `company` field, e.g. rotowire). The provider is the real provenance — what gets stored in `source`. |
| **Window function** | Aggregate computed over a partition of rows while keeping every row (vs `GROUP BY` which collapses). `OVER (PARTITION BY ... ORDER BY ...)`. |
| **`RANK` / `DENSE_RANK` / `ROW_NUMBER`** | Tie handling: `RANK` shares then skips (1,1,1,4); `DENSE_RANK` shares no skip (1,1,1,2); `ROW_NUMBER` always distinct (1,2,3,4). |
| **CTE (`WITH ... AS`)** | Named subquery; materializes a result the outer query can reference. Used to compute window ranks before filtering on them (window functions evaluate after WHERE). |
| **`NULLS FIRST` / `NULLS LAST`** | Controls where nulls sort in an `ORDER BY`. Postgres `ASC` defaults to `NULLS LAST`. Relevant to ranking nullable columns. |
| **Value rank vs market rank** | Value rank = standing by the engine's projected points; market rank = standing by ADP. The gap is the draft edge — engine values a player higher/lower than the market drafts him. |
| **VORP (Value Over Replacement Player)** | Projected points minus the last *startable* player at the position (replacement level, from league size + roster slots). Makes positions comparable on one scale and exposes tier cliffs. Phase 3–4; deterministic → engine. |
| **Tier cliff** | A steep points dropoff between adjacent players at a position; shows as a VORP collapse. The "is this gap a cliff or noise?" judgment → LLM (Phase 4). |

---

## When I'll Use Each Concept

| Concept | Phase |
|---------|-------|
| Structured output | ✅ Done (Week 2 Day 4; reused Phase 2 — `BeanOutputConverter` verdict classification) |
| System prompts | ✅ Done (Week 2 Day 5) |
| Context management | Phase 2+ (when prompts get large with player data) |
| Conversation memory | ✅ Done differently (Phase 3 — multi-turn parse/refine/confirm via a **stateless typed-object carry**, not `ChatMemory`: the state is a partial `ParsedLeague` in the payload). `ChatMemory` deferred to Phase 4's genuinely conversational agent |
| RAG (SQL-based) | ✅ Done (Phase 2 — recent actuals retrieved from `player_stats`, injected into the verdict prompt to ground role disagreements) |
| RAG (vector-based) | Phase 4 (deferred with teams/schedule/depth-chart ingestion — news & role context) |
| Embeddings + pgvector | Phase 4 (deferred — accompanies the vector news/role RAG) |
| Few-shot prompting | Phase 2+ (consistent classification/ranking) |
| Tool calling | Phase 4 (draft assistant calls my Java methods) |
| Agent loop | Phase 4 (draft assistant reasons across multiple steps) |
| Chain of thought | Phase 4 (explainable draft recommendations) |
| Guardrails | ✅ Done in a stronger form (Phase 3 — the spec→resolver→domain **type boundary**: the LLM has no field to write a scoring number into; enforcement is the type shape, not the prompt) |
| Streaming | Phase 4+ (responsive UI) |
| Fantasy scoring | ✅ Done (Week 3 Day 4) |
| Position enum | ✅ Done (Week 3 Day 4) |
| Interface extraction | ✅ Done (Week 3 Day 4) — StatLine for scoring abstraction |
| Unit testing (JUnit 5) | ✅ Done (Week 3 Day 4) — ScoringServiceTest |
| External projections | ✅ Done (Week 4 Day 2) — Sleeper/rotowire seasonal projections |
| Player scoring table | ✅ Done (Week 4 Day 1) — Flyway V4, entity, repository, wired into sync |
| Entity/DTO separation | Phase 1–2 (new entities get DTOs from start; existing refactored when touched) |
| League settings persistence | ✅ Done (Phase 3 — `league_config` V10, written only by the confirm gate; resolved scoring as typed NUMERIC columns + tactics JSONB) |
| Lombok | ✅ Done (Week 4 Day 1) — all entities refactored |
| @PrePersist/@PreUpdate | ✅ Done (Week 4 Day 1) — JPA lifecycle callbacks for audit timestamps |
| @Query with JPQL | ✅ Done (Week 4 Day 1) — findDistinctYears() |
| Computed/derived tables | ✅ Done (Week 4 Day 1) — player_scoring, no FK, application-guaranteed integrity |
| Projections table + ADP capture | ✅ Done (Week 4 Day 2) — player_projections, FK to player, per-format ADP, source provenance |
| Parameterized scoring rules (ScoringRules) | ✅ Done (Phase 3 Commit 1 — `ScoringFormat` graduated into `ScoringRules` + `ReceptionFormat`; `Position` threaded into `ScoringService.calculate` for TE premium; six presets reproduce the regression anchors) |
| Scoring source-routing (projections vs actuals) | ✅ Done (Week 4 Day 3) — config-driven (current-season boundary) |
| Pagination (Page/Slice, PagedModel/VIA_DTO) | ✅ Done (Week 4 Day 4) — leaderboard |
| Composite/assembled DTOs | ✅ Done (Week 4 Day 4) — player profile |
| JPQL constructor expressions | ✅ Done (Week 4 Day 4) — leaderboard rows |
| Hibernate 6 ad-hoc entity joins | ✅ Done (Week 4 Day 4) — scoring⋈player |
| `@Enumerated(EnumType.STRING)` | ✅ Done (Week 4 Day 4) — scoringFormat field |
| Positional rank (derived, CASE count) | ✅ Done (Week 4 Day 4) — "QB8" in profile |
| Window functions (`RANK() OVER`) | ✅ Done (Week 5 Day 1) — leaderboard value/market rank overlay |
| Mockito service tests (no Spring) | ✅ Done (Week 4 Day 5) — profile/scoring/sync services |
| `@WebMvcTest` controller slices | ✅ Done (Week 4 Day 5) — all four controllers |
| `@DataJpaTest` + Testcontainers | ✅ Done (Week 4 Day 5) — repo queries on real Postgres |
| Whole-app risk-based test suite | ✅ Done (Week 4 Day 5) — fixture factory + Claude Code delegation |
| `@RestControllerAdvice` + `ProblemDetail` (RFC 9457) | ✅ Done (Week 5 Day 1) |
| Bean Validation (`@Validated`, `@Min`/`@Max`, the two exceptions) | ✅ Done (Week 5 Day 1) |
| Native query + projection interface | ✅ Done (Week 5 Day 1) — ranked leaderboard |
| CTEs + rank-then-filter + SQL logical order | ✅ Done (Week 5 Day 1) |
| Active-player filter | ✅ Done (Week 5 Day 1) — built, tested, dormant |
| VORP / tier cliffs | Phase 4 — engine computes VORP, LLM reasons about cliffs (deferred out of Phase 3) |
| NL config parsing (spec → resolver → domain) | ✅ Done (Phase 3 — narrow LLM spec, deterministic resolver owns the flag→number registry) |
| Object-level / cross-field validation | ✅ Done (Phase 3 — `LeagueRulesValidator` + `DraftTacticsValidator`; severity-classified, collect-all, value-bearing messages) |
| Validate-and-repair loop (multi-turn) | ✅ Done (Phase 3 — stateless typed-object carry, turn cap, confirm gate) |
| Refine drift guard | ✅ Done (Phase 3 — deterministic field diff; drift surfaced as ASSUMPTION) |

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

## Phase 1 — Data Foundation ✅ COMPLETE

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

### Week 4 Day 2 — Projections: Schema, Sync Pipeline + the Scoring/LLM Boundary
**Time:** ~4h

**What I built**
- `V5__create_player_projections_table.sql` — composite PK `(player_id, year)`, FK to `player`, three per-format ADP columns (`adp_std`/`adp_half_ppr`/`adp_ppr` as `NUMERIC(5,2)`), index on `year`
- `PlayerProjectionId` — composite ID class (Lombok, `Serializable`, `@EqualsAndHashCode`)
- `PlayerProjection` — JPA entity (Persistable, Lombok `@Builder`, `@PrePersist`/`@PreUpdate`, two-field FK pattern, fields named to match `StatLine` so Day 3's wiring is a one-word change)
- `PlayerProjectionRepository` — `findByYear`, `findByPlayerId`
- `SleeperProjection` + `SleeperProjectionData` — DTO records (defensive parsing, `@JsonIgnoreProperties(ignoreUnknown = true)`)
- `SleeperClient.fetchProjections(int season)` — third Sleeper endpoint
- `PlayerProjectionSyncService` — `@Transactional`, filter → map → upsert
- `PlayerProjectionController` — `POST /api/projections/sync/{season}`, `GET /api/projections/player/{id}`
- Loaded 2026 rotowire projections; verified Mahomes (4046): 3773 pass yd, 28 pass TD, 11 INT, ADP ~100, `source=rotowire`, `gamesPlayed=17`

**Verified the API before writing anything** — curled `/projections/nfl/2026` first. The Phase 0 lesson held: the payload was richer and shaped differently than I'd have assumed (embedded full `player` object, per-format ADP, provider-computed points, distance-bucketed receptions). Writing a migration against assumed field names would have been wrong.

**Design decisions**

1. **FK on source-data tables, no FK on computed tables.** `player_projections` references real players, so it gets a FK to `player` — exactly like `player_stats`. This corrected my earlier instinct to skip the FK "like `player_scoring`." The no-FK rule applies to *computed* tables I fully own and can recompute (scoring), not to *external source data* keyed to real entities (stats, projections). A projection pointing at a non-existent player is a genuine data error the DB should reject.
   - **Interview line:** *"Foreign keys belong on tables holding external source data that references real entities — stats, projections — where a row pointing at a non-existent player is a genuine data error. I drop them on computed tables I fully own and can recompute, like scoring, where the FK is constraint-management overhead protecting against nothing."*

2. **`source` from the payload's `company` field, not hardcoded.** Sleeper is the *aggregator*; the actual provider is `company: "rotowire"`. Storing real provenance is the foundation for Phase 2's multi-source reconciliation. Hardcoding "SLEEPER" would have thrown away the one piece of info that makes the column worth having.
   - **Interview line:** *"I put a source discriminator on the raw projections table because reconciling multiple providers is a known upcoming requirement. I deliberately kept it off the computed scoring table — there the year already tells you historical-versus-projected, so a source column would solve a problem that doesn't exist."*

3. **ADP is per-format → three columns; `999` → NULL.** ADP varies by PPR setting exactly like points do, so one column won't do. Sleeper encodes "unranked" as the sentinel `999`; the ETL converts it to NULL (which also keeps values inside `NUMERIC(5,2)`), so sorting doesn't bury undrafted players mid-draft.

4. **`gamesPlayed = 17`, overriding rotowire's `gp = 18`.** 18 is the schedule length (18 weeks, one bye), not games anyone plays — and it's a constant across every player, so it's a label, not a projection. Cleansed at the ETL layer to keep projected PPG comparable to historical 17-game seasons.

5. **Provider points (`pts_*`) parsed but NOT persisted — a cross-check, not a source of truth.** Rotowire ships `pts_std`/`pts_half_ppr`/`pts_ppr`, but my `ScoringService` owns fantasy points. I parse them only to validate my engine on Day 3. They use rotowire's conventions (4pt passing TD, −1 per INT), which differ from mine (−2 per INT), so the cross-check is: non-QBs match exactly, QBs differ by exactly the interception count.
   - **Interview line:** *"Different projection providers use different scoring conventions — interceptions might be −1 or −2 depending on the platform. I treat a provider's precomputed points as a cross-check against my own scoring engine, never as ground truth, because my engine encodes the league's chosen rules, not the provider's."*

6. **Single-source PK now; multi-source reconciliation deferred to Phase 2.** PK is `(player_id, year)` — one consensus projection per player/season. The scoring table has no source column (year discriminates), which is exactly what *forces* reconciliation upstream: Phase 2 adds a raw-per-source feeder, reconciles to one consensus row, then scores. Clean separation of "raw collection" from "consensus."

7. **The scoring/LLM boundary (the conceptual core of the day).** A scoring convention the provider doesn't give me — 6pt passing TD, TE premium — is *deterministic math*, so it lives in the scoring engine, never the LLM.
   - 6pt passing TD is already handled: the engine computes it from the raw projected stat line via `STANDARD_6PT`. I never depend on rotowire's 4pt `pts_*`.
   - TE premium (1.5/reception for TEs) is a *position-dependent* rule my `ScoringFormat` enum can't express (it only varies reception value and passing-TD value). The fix is a parameterized `ScoringRules` value object plus passing player position into scoring — deferred to Phase 3 (user customization), where arbitrary formats get a real use case. The enum isn't thrown away; it graduates into a registry of preset rule-sets.
   - The LLM's job is the *strategic interpretation*: comparing my engine's league-specific value against market ADP (a mostly-standard signal). "Goes round 8 in the standard market, worth round 6 in your 6pt league" — that gap is the product.
   - **Interview line:** *"Scoring conventions are deterministic arithmetic, so they live in the scoring engine, never the model. The engine prices a player exactly for any format; the LLM reasons about strategy — how that league-specific value compares to market ADP, positional scarcity, roster construction. I never ask the model to estimate something I can compute."*

8. **Did NOT auto-trigger scoring from the projection sync.** The stats sync ends by calling `computeAndSaveForSeason`, but that reads `player_stats`, which is empty for 2026. Scoring projections requires routing by source (projections for the upcoming season, actuals for past ones) — that's the Day 3 decision, so I deferred it rather than copy a pattern that doesn't transfer.
   - **Interview line:** *"I didn't copy the stats sync's auto-trigger-scoring step into the projection sync, because scoring has to route by data source — projections for the upcoming season, actuals for completed ones. Reusing that path blindly would score an empty table."*

**Mistakes I made and lessons**

1. **`fetchProjections` used `statsBaseUrl` instead of `projectionsBaseUrl`.** The two endpoints share a response schema, so it compiled, returned 200, deserialized cleanly, and silently produced *stats masquerading as projections* (null `company`/ADP/pts). The Phase 0 "HTTP 200 ≠ logical success" lesson with a twist: when two endpoints share a shape, neither the compiler nor Jackson nor the status check can catch a wrong-endpoint bug — only the data is wrong.
   - **Interview line:** *"When two API endpoints share a response schema, a wrong-endpoint bug passes compilation, deserialization, and the status check — only the data is wrong. Structural success isn't semantic correctness; the type system can't protect you when the shapes match."*

2. **Hardcoded `source = "SLEEPER"`** instead of `source.company()` — would have discarded provenance. Also guarded against a null `company`: against the `NOT NULL` column it would roll back the entire `@Transactional` batch, so one bad row shouldn't nuke 600 good ones.

3. **Three copy-paste string leftovers** — `fetchProjections`'s catch said "stats," the controller response said "stat lines." The logic copies cleanly; human-readable strings slip through because nothing references them. Lesson: after copying a method, grep the copy for the old noun.

4. **`isNew()` leaked as `"new": true` in JSON.** `@JsonIgnore` on the `isNew` *field* doesn't suppress the `isNew()` *getter* — Jackson names the field property "isNew" but turns a boolean `isX()` getter into a separate property called "new" (JavaBeans), so the field annotation doesn't cover the getter. Fix: `@JsonIgnore` on the `isNew()` method, same as `getId()`. Applies to `PlayerStats` too (backported — `@JsonIgnore` now on `isNew()` and `getId()` on both entities).
   - **Interview line:** *"Jackson turns a boolean getter `isNew()` into a property named `new`, separate from the field `isNew`. So `@JsonIgnore` on the field doesn't suppress the getter — you annotate the method. Classic boolean-property naming gotcha."*

5. **`BigDecimal.valueOf(double)` for ADP, not `new BigDecimal(double)`** — the Week 3 Day 4 trap again; the double constructor would store `13.40000000…`.

6. **`TypeReference<>` diamond vs explicit** — the diamond compiles in Java 17 (target-typed) but `TypeReference` exists to carry an explicit reified type; relying on inference is fragile under refactor. Kept it explicit, matching `fetchStats`.

### Project structure (updated)
```
read-option/
└── src/
    ├── main/java/app/readoption/
    │   ├── playerprojection/                    ← NEW PACKAGE
    │   │   ├── PlayerProjection.java            ← Persistable, Lombok, two-field FK
    │   │   ├── PlayerProjectionId.java          ← Lombok @EqualsAndHashCode
    │   │   ├── PlayerProjectionRepository.java
    │   │   ├── PlayerProjectionSyncService.java ← @Transactional, company→source, gp→17, 999→null
    │   │   └── PlayerProjectionController.java   ← sync + by-player endpoints
    │   └── sleeper/
    │       ├── SleeperProjection.java           ← NEW DTO (top-level)
    │       └── SleeperProjectionData.java        ← NEW DTO (nested stats + ADP + pts)
    └── resources/db/migration/
        └── V5__create_player_projections_table.sql  ← NEW
```

---

### Week 4 Day 3 — Scoring Projections: `Scorable` Interface + Source Routing
**Time:** ~3h

**What I built / changed**
- `Scorable` interface (`extends StatLine`, adds `getPlayerId()`) — the polymorphic contract that lets one scoring loop handle either source
- `PlayerStats`: `StatLine` → `Scorable`. `PlayerProjection`: added `Scorable`, and dropped the now-unused `@ManyToOne player` association (DB FK retained) to silence an `HHH000502` warning
- `PlayerProjectionRepository.findDistinctYears()` (JPQL)
- `PlayerScoringService` refactor: injects `PlayerProjectionRepository` + `@Value` current-season; `computeAndSaveForSeason` routes by the configured boundary; private `scoreAndSave(List<? extends Scorable>, …)` holds the generalized per-row × 6-format loop; `recomputeAllSeasons` unions distinct years from both tables
- `application.properties`: `readoption.current-season=2026`
- Projection sync re-adds the `computeAndSaveForSeason(season)` trigger
- Validated 2026 scoring against rotowire's `pts_*`
- **Follow-up cleanups (post-validation):** set the `ScoringService` interception value to −2 (the league rule) and ran `recomputeAllSeasons`; backported `@JsonIgnore` on `isNew()` + `getId()` to `PlayerStats` (kills the `"new": true` leak); dropped the unused `@ManyToOne player` from `PlayerStats` (DB FK retained)

**Design decisions & lessons**

1. **`Scorable` — Interface Segregation in practice.** `StatLine` stays the pure scoring contract (what `calculate()` and the unit tests need, no identity). `Scorable extends StatLine` adds the one thing the persistence loop needs — `getPlayerId()`. Both entities implement it, so the loop is written once over `List<? extends Scorable>`; `calculate(StatLine)` is unchanged.
   - **Interview line:** *"I segregated the interfaces by consumer: `StatLine` is what the scoring formula needs — pure stats, trivially stubbed in unit tests. `Scorable` extends it with the player id, which only the persistence layer needs. Same data, two contracts, each minimal for its caller."*

2. **Source routing: config-driven, not data-presence (learned the hard way).** First attempt routed by data presence — "if `player_stats` has the year, score stats, else projections." It scored every 2026 player to **0.00**. Cause: I'd synced 2026 *stats* earlier, and Sleeper returns zero-stat rows for a season that hasn't been played — present but empty. Switched to a configured boundary: `season < currentSeason` → stats, else projections. Deterministic, immune to empty rows, and can't be fooled by an accidental future-season stats sync.
   - **Interview line:** *"I route scoring by an explicit current-season boundary, not by inferring it from data presence. A provider can return rows for a season that hasn't happened — present but empty — so 'the table has rows for this year' doesn't mean the year was played. A configured boundary is deterministic; inference from data shape isn't."*

3. **The cross-check as a regression oracle — and the bug it caught.** Saquon (no INT) matched rotowire to the cent: 208.50 / 226.00 / 243.50 = `pts_std` / `pts_half_ppr` / `pts_ppr`, validating the full rushing/receiving/PPR/2pt/fumble path through projections. Mahomes exposed a real bug: he scored 278.62, which backs out to **−1 per interception** — but my league rule is **−2**. The `ScoringService` constant didn't match the intended rule. Fixed the constant to −2 and ran `recomputeAllSeasons`; Mahomes is now 267.62 (4PT) / 323.62 (6PT), sitting exactly the interception count (11 pts) below rotowire's −1 numbers — the intended convention difference. Saquon, with no interceptions, matched both before and after, which is precisely what let me isolate the discrepancy to the INT term.
   - **Interview line:** *"I treat a provider's precomputed points as a regression oracle. When my engine diverges, it's either a convention difference I can name to the decimal or a bug — here it surfaced that my interception value wasn't what I believed. The cross-check caught the gap between intent and implementation."*

4. **`HHH000502` and the read-only association.** On the re-sync UPDATE path, Spring Data `merge()` saw `PlayerProjection.player` (the read-only `@ManyToOne`) as "modified" to null and warned it wouldn't write it. Benign — `player_id` is owned by the raw `@Id` and untouched — but noisy on every re-sync. Dropped the unused association (never navigated); the V5 database FK still enforces integrity. Dropping the JPA mapping is not dropping the constraint.

5. **Operational rule:** don't sync stats for an unplayed season. Stats sync is for completed seasons; the upcoming season lives in projections. Routing now protects scoring from the mistake, but the junk rows still shouldn't exist (cleaned 2026 out of `player_stats`).

6. **Transaction joining:** the projection sync's call to `computeAndSaveForSeason` is a cross-bean call through the proxy, so with default `REQUIRED` propagation it joins the sync's transaction — projections and their scoring commit or roll back together.

7. **Decoupling win:** after the refactor `PlayerScoringService` no longer imports the concrete `PlayerStats`/`PlayerProjection` types — it works entirely through `Scorable` and the two repositories.

### Project structure (updated)
```
read-option/src/main/java/app/readoption/
├── scoring/
│   └── Scorable.java                    ← NEW (extends StatLine, adds getPlayerId)
├── playerstats/PlayerStats.java         ← now implements Scorable
├── playerprojection/
│   ├── PlayerProjection.java            ← implements Scorable; dropped @ManyToOne player
│   └── PlayerProjectionRepository.java  ← + findDistinctYears()
└── playerscoring/
    └── PlayerScoringService.java        ← config-driven routing, scoreAndSave(List<? extends Scorable>), union recompute
```

---

### Week 4 Day 4 — Query/Read Endpoints: Pagination, Composite DTOs, Position Filter + Positional Rank
**Time:** ~4h

**What I built**
- Two read endpoints over the scored data — a paginated **leaderboard** and a composite **player profile**. These are the queryable face of all the scoring/projection work. The primary *future* consumer is the Phase 4 LLM agent (RAG / tool-calling fuel), not a UI; the consumer *now* is me/curl/tests.
- `SpringDataWebConfig` — `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`
- DTO records: `LeaderboardRow` (flat, from a join); `PlayerProfile` + `SeasonScore` + `ProjectionScore` (hierarchical composite)
- `AdpBucket` enum + `ScoringFormat.adpBucket()` + `PlayerProjection.adp(AdpBucket)` — format→ADP-column mapping
- Leaderboard `@Query`: JPQL constructor expression over a Hibernate-6 ad-hoc entity join, explicit `countQuery`, fixed `ORDER BY total_points DESC`, optional position filter (null-or-match guard), `MAX_PAGE_SIZE` clamp
- `PlayerProfileService` — service-assembled composite, `@Transactional(readOnly = true)`
- `PlayerNotFoundException` (`@ResponseStatus(NOT_FOUND)`)
- **Migrated `PlayerScoring.scoringFormat` from `String` to `ScoringFormat` enum** via `@Enumerated(EnumType.STRING)` — no data migration
- **Positional rank ("QB8")** — derived count query with a searched `CASE` picking the ADP column by format bucket

**API shape**
- `GET /api/scoring/leaderboard?season=2026&format=STANDARD_6PT&position=RB&page=0&size=25` — season/format default via config, position optional
- `GET /api/players/{id}/profile?format=STANDARD_6PT` — history (past seasons) + current projection + positional rank

**Frontend decision (settled this session)**
- No frontend now, probably not until after Phase 4, possibly never as a dashboard. The product is conversational — its natural UI is a chat box, not a data grid. The CV differentiator is the Phase 4 AI agent, not React.
- When/if built: Claude builds it (Claude Code or a throwaway artifact); I stay at read-and-tweak comprehension. **Own what I'm selling (backend + AI integration), delegate adjacent scaffolding (frontend).**
- **Interview line:** *"I used AI coding tools to scaffold the frontend so I could concentrate my own engineering on the data pipeline and the LLM integration — the parts that are the actual product. Deciding what to build by hand versus delegate to AI tooling is part of how I work now."*

**Who consumes these endpoints (the reframe that made pagination make sense)**
- A REST endpoint is a **published contract** consumed by whatever calls it — another service, a test, or an LLM tool call. It doesn't know or care who calls it (same decoupling as a Camel route or a DB view). Building the API before any UI is normal — the API *is* the product surface.
- Consumers, in order: (1) me/curl/tests now (sanity-check the scoring — Mahomes should top the QB board), (2) the Phase 4 agent (leaderboard = "top available players", profile = grounded context for a specific pick), (3) a thin UI eventually.
- Pagination's real justification here isn't "page 3 of 13 UI" — it's **bounded reads**, the Phase 0 *context bomb* lesson at the API layer. You don't dump 300 players into a prompt; you take top N. The `MAX_PAGE_SIZE` clamp is that lesson made concrete. Mentally it's the same as Oracle `OFFSET n ROWS FETCH NEXT m`.

**Pagination**
- `Page<T>` = content + total count + total pages (runs a second COUNT query). `Slice<T>` = content + "is there a next page?" (no count, over-fetches one row). `Page` for "3 of 13", `Slice` for infinite scroll.
- **Don't return `Page` raw from a controller.** Since Spring Data 3.1 it logs a warning — `PageImpl` is an internal type with no JSON-stability guarantee. Spring Data 3.3 (Boot 3.5 ships it) added a non-HATEOAS `PagedModel`; opt in with `VIA_DTO` and every `Page<T>` serializes as a stable `{ content, page: { size, number, totalElements, totalPages } }` envelope. Same principle as versioned service contracts / ActiveMQ schemas — don't leak an internal type into a published contract.
- **Bake the sort, don't expose `Sort`.** A leaderboard has one correct order (points desc) — `ORDER BY` in the query, only `page`/`size` from the client. Arbitrary `Sort` lets a client request a non-existent property (500) or sort an unindexed column.
- **Interview line:** *"Returning a `Page` straight from a controller has been discouraged since Spring Data 3.1 because `PageImpl` is an internal type with no JSON-stability guarantee. Spring Data 3.3 added a non-HATEOAS `PagedModel`; I enable it via `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)`, or map to my own response record when I want to own the contract outright."*

**The two-query contrast (the core lesson of the day)**
- **Leaderboard** = constructor-expression projection over a join, hundreds of rows across two tables → don't hydrate entities.
- **Profile history** = derived query returning entities, one player's ≤6 rows from a single table with no associations → loading entities and mapping in Java is cleaner. Don't over-apply constructor expressions when N is tiny.
- **Hibernate 6 ad-hoc entity join:** `JOIN Player p ON p.id = s.playerId` between entities with **no mapped association** — impossible in older Hibernate (native SQL only). Lets `player_scoring` stay FK-free and association-free yet joinable in HQL.
- **JPQL constructor expression:** `SELECT new <FQN>(...)` projects rows straight into a DTO constructor — no entity hydration, no lazy proxies. Records are ideal targets.
- **Count-query gotcha:** a `@Query` with a projection + join can't auto-derive its count, so supply an explicit `countQuery` — the cheapest statement returning the same total. A filter on a joined table forces the join into the count query too, or `totalElements` desyncs from the content.
- **Interview line (entity join):** *"Hibernate 6 supports ad-hoc entity joins between entities with no mapped association. Before that, joining unmapped entities in JPQL was impossible — you dropped to native SQL. It's what lets me keep computed tables FK-free and association-free while still joining them in HQL when a read needs it."*
- **Interview line (constructor expression):** *"A JPQL constructor expression projects query results directly into a DTO constructor, skipping entity hydration and the persistence context — faster and immune to lazy-proxy serialization. Records are ideal targets because their canonical constructor matches the projection."*
- **Interview line (count query):** *"For a `@Query` with a projection and a join, Spring Data can't derive the count, so I supply an explicit `countQuery` — and a filter on a joined table has to appear in the count query too, or the filtered content and the total come from different row sets."*

**Composite DTO (profile) — three approaches weighed**
- Interface projection: flat subset of ONE query → wrong for a nested multi-source response.
- Constructor expression: great per flat row, but one query can't build a parent-with-collection.
- **Service-assembled record DTO (chosen):** fetch the parts, split history vs projection **by year**, select the ADP column by format, assemble. Explicit, flexible, testable.
- The year-split (`year < currentSeason` = history, `== currentSeason` = projection) is **free because Day 1 refused a source column and let the year discriminate** — that decision paid rent here.
- **Interview line:** *"Interface projections are for a flat slice of a single query. The moment a response aggregates multiple sources into a nested shape, that's a service-assembled DTO — I still use constructor-expression queries to pull flat rows so no entity leaks into the read path, but the composition is plain Java in the service where it's explicit and testable."*

**What DTOs are *for* here (consolidated)**
- The response shape doesn't match any single entity (leaderboard joins two tables; profile spans three + many rows) — the DTO *is* that shape.
- Keeps internal junk out of JSON (`Persistable.isNew`/`getId`, audit timestamps, lazy proxies) — the `@JsonIgnore` band-aids were a *symptom* of returning entities; DTOs are the *cure*.
- Decouples the API contract from the schema — returning entities makes JSON a mirror of the tables, so any column change breaks consumers (same instinct as an ActiveMQ message schema vs the raw row). A DTO is roughly a SQL view shaped for one consumer, except it can carry logic a view can't.
- Don't cargo-cult: a DTO isn't mandatory when the response genuinely *is* one entity. The trigger is **divergence** — multiple sources, aggregated rows, or fields to hide.
- **Interview line:** *"A DTO decouples the API contract from the database schema. Returning entities makes the JSON a mirror of the tables, so any schema change breaks consumers and internal fields leak onto the wire. I introduce one the moment a response diverges from a single entity — almost always for reads that join or aggregate."*

**`String` → enum migration on a composite-key field (Option B)**
- Leaderboard first failed: `Could not convert ScoringFormat to String` — `PlayerScoring.scoringFormat` was a `String` field; I'd bound an enum param to a String path. JPQL is parsed at runtime, so the compiler couldn't catch it ("structural success ≠ semantic correctness" one layer down).
- Two fixes: (A) keep the field `String`, pass `.name()` at the repo boundary; (B) map it `@Enumerated(EnumType.STRING)` end to end. Chose **B** for type safety.
- **No data migration / no schema change:** the column already stored the enum names as text, so `EnumType.STRING` maps onto the exact same varchar values — Java type changes, data doesn't, `ddl-auto=validate` still passes.
- IdClass field type must match the entity `@Id` type, so `PlayerScoringId.scoringFormat` became the enum too. Enum `equals`/`hashCode` is identity-based and correct for a key. Write path: `.scoringFormat(format)` instead of `.scoringFormat(format.name())`.
- **Never `EnumType.ORDINAL`** — it stores the declaration index, so reordering/inserting an enum constant silently remaps every existing row.
- **Interview line:** *"`@Enumerated(EnumType.STRING)` persists the enum's name — stable and readable. `ORDINAL` stores the declaration index, so reordering a constant silently corrupts existing rows; I never use it. Because the column already held the names as text, switching to STRING needed no migration. I avoid retyping an enum field that's part of a composite key unless the payoff is worth it, since that's the machinery the upsert and equals/hashCode rely on."*

**Optional filter (position)**
- Null-or-match guard in a single query: `AND (:position IS NULL OR p.position = :position)` — inert when null, filters when present. Avoids duplicate methods. Once filters multiply → JPA Criteria/Specifications.
- Param is `String` (column is `String`) but taken as the `Position` enum at the controller (free 400 on garbage) then `.name()`-bridged — enum at the boundary, string at the column, same seam as the scoringFormat fix.
- `required = false`, **no `defaultValue`** — "all positions" is the *absence* of the filter (null), not a value (contrast with season/format which have natural defaults).
- The guard must be in the count query too (and so must the `Player` join it depends on).
- **Interview line:** *"Optional filters I express as a null-or-match guard in a single query rather than overloaded methods, and I mirror the guard — and any join it needs — in the count query so pagination totals stay correct. Once filters multiply I switch to Criteria/Specifications."*

**Positional rank ("QB8") — derived, not stored**
- "QB8" = how many same-position players have a better ADP, plus one. A **pure function of (position, overall ADP)** — derived on read, never a stored column (same "don't persist what you can recompute" as the scoring table / year-discriminator).
- Distinguished integer **positional rank** (QB8, derivable) from a decimal **measured positional ADP** (8.3, a separate market feed I don't have and don't need). Built the rank; skipped the decimal. Fits the division of labor: Sleeper gives raw overall ADP, Java computes the positional view.
- Implementation: a count `@Query` joining `Player`, with a **searched `CASE`** picking the ADP column by format bucket (`WHEN :bucket = 'STANDARD' THEN pr.adpStd ...`) — can't pick a column by parameter in JPQL, and the searched form is the safer HQL shape. `rank = count(better ADP) + 1`.
- **The null trap (real correctness bug avoided):** a player with no ADP compares as *unknown* against everyone, so the naive count is 0 → rank 1 → every undrafted player looks like the consensus #1 at his position. Guarded at the service: no ADP ⇒ null rank, don't even run the query. The sentinel-null also handles itself in the count: `null < :playerAdp` is unknown, so undrafted players are excluded without an explicit `IS NOT NULL`.
- **Forward hook (Phase 4):** compute the rank twice — once over market ADP, once over my engine's projected points. The **gap between market rank and value rank is the draft edge** the LLM reasons about. A single-player rank is a count; ranking *everyone at once* (leaderboard overlay) is the window-function (`RANK() OVER (PARTITION BY position ORDER BY ...)`) job.
- **Interview line (derived):** *"Positional rank is derived, not stored — a ranking over ADP partitioned by position, recomputed on read. And I'd compute it twice: over market ADP and over my own projected value. The divergence between a player's market rank and value rank is the signal the draft assistant reasons about."*
- **Interview line (count vs window):** *"For one player's rank I count how many same-position players have a better ADP — a single indexed count, not a full `RANK()` window over the whole position. The window function is for when I need every player's rank at once, like a leaderboard overlay."*
- **Interview line (null trap):** *"Ranking by 'count of better values plus one' has a null trap: a player with no value compares as unknown against everyone, so the naive count is zero and he ranks first. I guard it at the service — no value means no rank, returned as null — rather than letting a missing value masquerade as the best."*

**Controller-vs-service layering**
- Leaderboard goes controller → repository directly: one query, no assembly, a service would only delegate. Profile gets a service: real logic (assembly, year-split, ADP bucket, rank). Introduce a layer when it holds something.
- Default for an optional param lives at the **controller** via `${readoption.current-season}` placeholder in `defaultValue` (resolved like `@Value`, then converted to int); the service takes an explicit non-null value. Same property feeds two consumers in two layers (controller default vs business routing) — not duplication.
- **Interview line:** *"A default for an optional request param is an API concern, so it lives at the controller and binds to a config property via a placeholder in `defaultValue`. The service takes the resolved value explicitly and never invents one, so it stays deterministic and unit-testable."*

**Production items deferred (noted for later)**
- `@Min/@Max` validation on `page`/`size` (negative page → 500 from `PageRequest.of`)
- `@ControllerAdvice` for a consistent JSON error body across 404/400
- Active-player filter for historical leaderboards
- Leaderboard market-rank overlay via window function

**Validation**
- Leaderboard top sane (elite QBs top STANDARD_6PT). `position=RB`/`WR` give correct boards, `totalElements` drops vs unfiltered. Bad `position`/`format` → 400, bad player id → 404.
- Profile: Mahomes (4046) history + 2026 projection + plausible single-digit QB positional rank.
- VIA_DTO envelope confirmed (`page` block present; no `PlainPageSerializationWarning`).

### Project structure (updated)
```
read-option/src/main/java/app/readoption/
├── config/
│   └── SpringDataWebConfig.java             ← NEW (@EnableSpringDataWebSupport VIA_DTO)
├── player/
│   ├── PlayerController.java                ← + GET /{id}/profile
│   ├── PlayerProfileService.java            ← NEW (composite assembler, readOnly tx)
│   ├── PlayerProfile.java                   ← NEW (composite DTO)
│   ├── SeasonScore.java                     ← NEW (history row DTO)
│   ├── ProjectionScore.java                 ← NEW (projection DTO + positionalRank)
│   └── PlayerNotFoundException.java         ← NEW (@ResponseStatus 404)
├── playerscoring/
│   ├── PlayerScoring.java                   ← scoringFormat now @Enumerated(EnumType.STRING)
│   ├── PlayerScoringId.java                 ← scoringFormat now ScoringFormat
│   ├── PlayerScoringRepository.java         ← + findLeaderboard (constructor expr + countQuery + position guard), findByPlayerIdAndScoringFormatOrderByYearAsc
│   ├── PlayerScoringController.java         ← + GET /leaderboard
│   └── LeaderboardRow.java                  ← NEW (flat join DTO)
├── playerprojection/
│   ├── PlayerProjection.java                ← + adp(AdpBucket)
│   └── PlayerProjectionRepository.java      ← + findByPlayerIdAndYear, countBetterAdpAtPosition (CASE)
└── scoring/
    ├── ScoringFormat.java                   ← + adpBucket()
    └── AdpBucket.java                       ← NEW (STANDARD/HALF_PPR/PPR)
```

---

### Week 4 Day 5 — Test Suite: Slices, Mockito, Testcontainers + Whole-App Coverage
**Time:** ~4h

**What I built**
- A risk-based test suite across the whole app, learning each test *shape* once by hand, then delegating the repetition to Claude Code under review.
- Four test patterns now exist as exemplars: (1) plain unit (no Spring) — `ScoringServiceTest`; (2) Mockito service (no Spring) — `PlayerProfileServiceTest`, `PlayerScoringServiceTest`, both sync-service tests; (3) `@WebMvcTest` controller slice — four controller tests; (4) `@DataJpaTest` + Testcontainers (real Postgres) — three repository tests.
- `TestFixtures` — central factory for valid test entities (player/scoring/projection/stat).
- `AbstractPostgresTest` — singleton Testcontainers Postgres base (`@ServiceConnection`, production `pgvector/pgvector:pg16` image).
- `ReadOptionApplicationTests.contextLoads()` smoke test (extends the Postgres base).

**Testing philosophy — risk-based, not uniform**
- Cover what can break and is costly when it does (domain logic, custom SQL, web wiring). Skip what's generated or trivial.
- **Deliberately NOT tested:** Lombok getters/setters, record DTOs, inherited Spring Data CRUD, ID-class equals/hashCode, framework behavior — testing these tests Lombok and Spring, not my code.
- **Interview line:** *"I don't chase a coverage percentage; I test by risk. Domain logic and custom SQL get thorough tests because that's where my bugs live and the compiler can't help. Framework-generated code — getters, record accessors, inherited repository methods — I don't test, because I'd be testing Lombok and Spring Data, not my own behavior."*

**The four test shapes (each owned once, then scaled)**

1. **Plain unit.** `ScoringService` tested through an anonymous `StatLine` — no Spring, no DB, milliseconds. The payoff of depending on an interface.

2. **Mockito service test.** `@ExtendWith(MockitoExtension.class)` + `@Mock` repos, no Spring context. Built the service by hand in `@BeforeEach` (not `@InjectMocks`) because the constructor mixes mocks with a plain `int currentSeason`.
   - `verify(mock, never())` is **behavior** verification, not state — the profile test proves the rank query is *never issued* when ADP is null (the null-trap guard short-circuits before the DB). State assertions check output; `verify` checks what the code *did*.
   - **Strict stubs** (default in `MockitoExtension`): an unused stub fails the test, so each test stubs only the repos on its actual path.
   - **Interview line:** *"Mockito's `verify(mock, never())` asserts on interactions, not return values — here I prove that with no ADP the service returns a null rank without ever issuing the ranking query. That's verifying behavior, which catches a class of bug pure output assertions miss."*

3. **`@WebMvcTest` controller slice.** Loads only the web layer for one controller; every collaborator it injects must be a `@MockitoBean` or the slice won't start.
   - **`@MockitoBean`, not `@MockBean`** — the latter is deprecated since Boot 3.4 (moved into Spring Framework 6.2 as the bean-override mechanism).
   - **`MockMvc`** runs the real dispatcher/binding/Jackson with no server; assert status + JSON via `jsonPath`.
   - **`ArgumentCaptor` tests my logic, not the framework's** — captured the `Pageable` the controller passed down and asserted size clamped to 100 (the context-bomb guard) when `size=500` was requested.
   - **400 on bad enum** = param binding fails before the method runs; **404 via `@ResponseStatus`** = throwing the mapped exception yields 404 through the web layer.
   - **`@Import(SpringDataWebConfig.class)`** pulls `VIA_DTO` into the slice so the paged envelope (`$.page`) renders — only needed for controllers returning `Page<>` (the stats/projection controllers don't, so they skip it).
   - **Interview line (slice):** *"`@WebMvcTest` loads only the web layer for one controller and mocks its collaborators, so I test request mapping, binding, status codes, and JSON serialization in isolation. `MockMvc` drives the real dispatcher without a running server."*
   - **Interview line (captor):** *"An `ArgumentCaptor` asserts on what the controller passed *down* to its collaborator — here, that an over-large page size was clamped before reaching the repository. It tests a transformation the controller performs without the real downstream component."*

4. **`@DataJpaTest` + Testcontainers.** JPA-only slice against a real Postgres.
   - **Singleton container** in a `static` block (not `@Container`/`@Testcontainers`, which start one per class) — boots once, every repo test reuses it.
   - **`@ServiceConnection`** (Boot 3.1+) auto-wires the datasource to the container — no `@DynamicPropertySource`.
   - **Production image** `pgvector/pgvector:pg16` via `asCompatibleSubstituteFor("postgres")` — same engine + extensions as prod.
   - **`@AutoConfigureTestDatabase(replace = NONE)`** disables the default H2 swap so the slice uses the container.
   - **Flyway builds the schema, Hibernate validates** — same startup sequence as prod, so the test also proves the migrations and entity mappings cohere.
   - **Transactional rollback** isolates each test — seed + test run in one rolled-back transaction.
   - **Interview line (Testcontainers):** *"I test repository queries against the same Postgres image I run in production via a singleton Testcontainers container wired with `@ServiceConnection`. Custom SQL — dialect-specific joins, `CASE`, null semantics — can pass on H2 and fail on Postgres, so the only test I trust runs on the real engine."*
   - **Interview line (DataJpaTest):** *"`@DataJpaTest` with `@AutoConfigureTestDatabase(replace = NONE)` lets Flyway build the real schema and Hibernate validate against it — so the same test that verifies my query also proves my migrations and mappings line up. Each test rolls back, so they're isolated without cleanup."*

**The highest-value SQL tests (what real Postgres proved)**
- Leaderboard: the Hibernate-6 ad-hoc entity join resolves (player names come through), order is points-desc, and the position filter narrows *both* content and `totalElements` — proving the explicit `countQuery` applied the same join+filter (the desync bug you can't see by eye).
- Positional rank `countBetterAdpAtPosition`: at once proved strictly-better counting, the **null-ADP player excluded** (`null < :adp` is unknown), the position filter ignoring other positions, and — via a PPR-bucket case that would count differently if it read `adpStd` — that the **searched `CASE` selects the right ADP column by parameter**.

**Fixture factory — one place for "a valid entity"**
- `TestFixtures.player/scoring/projection/stat` build fully-populated entities. Tests bypass the ETL layer, so each must satisfy every NOT NULL constraint itself — that knowledge lives once in the factory.
- The factory covers the *common* case with sensible defaults (PPG 20.00, gp 17, team "XX"); a test needing a specific edge value builds that entity inline rather than bloating the factory signature.
- **Triggered by a real failure:** `not-null property references a null or transient value: Player.firstName` — the hand-built `Player` set only `fullName`; in production `PlayerSyncService` fills first/last. Hibernate's entity-level NOT NULL check fires before the INSERT reaches Postgres.
- **Interview line:** *"I centralize valid test entities in a fixture factory because tests bypass the ingestion layer and would each re-discover every NOT NULL constraint. The factory encodes the common valid case with defaults; edge values are built inline rather than bloating its signature. A 'not-null property references a null or transient value' error is Hibernate's entity check catching it before the INSERT."*

**The Claude Code delegation workflow (the meta-lesson)**
- Only four test shapes in the whole app. Learn each once by hand (the part that's on the CV — I can explain slices, MockMvc, Testcontainers in a room), then hand the repetition to Claude Code with the exemplars as templates and an explicit do-NOT list, reviewing each class on a red/green bar.
- Reviewer discipline: close-read where the logic is real (the sync-service ETL cleansing — each rule, `999→null`, `gp 18→17`, gets its own assertion), and run the **whole** suite at the end to prove isolation, not just per-class runs.
- Same "own what I'm selling, delegate scaffolding" line: own the patterns and the review, delegate the repetition.
- **Interview line:** *"I scale a known test pattern with AI tooling by giving it the exemplars, hard constraints, and an explicit do-not-test list, then reviewing each class on a red/green bar — close reading where the logic is real, like ETL cleansing, and a full-suite run to prove isolation. I own the patterns and the review; the tool does the repetition."*

**What got built (A–E, via Claude Code under review)**
- **A — `PlayerScoringServiceTest`** (5, Mockito): season-routing boundary (`< current` → stats repo, `>= current` → projections repo), empty-source early return, `recomputeAllSeasons` unions both repos' distinct years and routes each correctly.
- **B — `PlayerStatsControllerTest` / `PlayerProjectionControllerTest`** (4 + 3, `@WebMvcTest`): status codes, 200 on valid sync/get, 400 on non-numeric path variable. No `@Import(SpringDataWebConfig)` since neither returns `Page<>`.
- **C — `PlayerStatsSyncServiceTest` / `PlayerProjectionSyncServiceTest`** (6 + 8, Mockito): a `doAnswer` capture in `@BeforeEach` (lenient under strict stubs) collects what's passed to `saveAll`; assertions cover filtering (unknown/null player ids, null stats), `gp 18→17`, `Double→Integer`, ADP `999→null`/`null→null`/valid→`BigDecimal.valueOf`, `source` from `company` with `"unknown"` fallback.
- **D — `PlayerStatsRepositoryTest`** (2, `@DataJpaTest`): added `TestFixtures.stat(...)` (games=17, gamesPlayed=0); `findDistinctYears()` ordering + dedup across players.
- **E — `ReadOptionApplicationTests`**: `contextLoads()` extended `AbstractPostgresTest` so the smoke test has a real container.

**Note — `doAnswer` capture under strict stubs**
- The sync tests capture the `saveAll` argument with `doAnswer` in `@BeforeEach`; declared there it's flagged as a potential unnecessary stub under `STRICT_STUBS`, so it's marked lenient. An alternative is `ArgumentCaptor` on a `verify(repo).saveAll(...)` after the call — either works; the team chose the `doAnswer` capture for symmetry across both sync tests.

### Project structure (tests)
```
read-option/src/test/java/app/readoption/
├── AbstractPostgresTest.java                ← singleton Testcontainers Postgres (@ServiceConnection)
├── TestFixtures.java                        ← valid entity factory (player/scoring/projection/stat)
├── ReadOptionApplicationTests.java          ← contextLoads() smoke (extends AbstractPostgresTest)
├── scoring/
│   └── ScoringServiceTest.java              ← plain unit (pre-existing)
├── player/
│   ├── PlayerProfileServiceTest.java        ← Mockito service (rank guard, year-split)
│   └── PlayerControllerTest.java            ← @WebMvcTest (profile 200/404)
├── playerstats/
│   ├── PlayerStatsControllerTest.java       ← @WebMvcTest
│   ├── PlayerStatsSyncServiceTest.java      ← Mockito (ETL cleansing)
│   └── PlayerStatsRepositoryTest.java       ← @DataJpaTest (findDistinctYears)
├── playerscoring/
│   ├── PlayerScoringServiceTest.java        ← Mockito (source routing, recompute union)
│   ├── PlayerScoringControllerTest.java     ← @WebMvcTest (+ @Import VIA_DTO, clamp captor)
│   └── PlayerScoringRepositoryTest.java     ← @DataJpaTest (entity join, position filter, count)
└── playerprojection/
    ├── PlayerProjectionControllerTest.java  ← @WebMvcTest
    ├── PlayerProjectionSyncServiceTest.java ← Mockito (ETL cleansing)
    └── PlayerProjectionRepositoryTest.java  ← @DataJpaTest (CASE rank, null exclusion)
```

---

### Week 5 Day 1 — Read-API Polish: RFC 9457 Errors, Pagination Validation, Active Filter + Window-Function Rank Overlay
**Time:** ~4h

**What I built (three chunks, closing Phase 1)**
- **Chunk 1 — error handling + validation.** `GlobalExceptionHandler` (`@RestControllerAdvice extends ResponseEntityExceptionHandler`) returning RFC 9457 `ProblemDetail` for every error path; `spring-boot-starter-validation` added; `@Min`/`@Max` on `page`/`size` with `@Validated` on the controller; oversized `size` switched from silent clamp to a rejected `400`.
- **Chunk 2 — active-player filter.** Optional `Boolean active` null-or-match guard on the leaderboard, mirrored into the count query; real-Postgres test proving the guard drops an inactive row.
- **Chunk 3 — market-rank window-function overlay.** New native query `findRankedLeaderboard` with four `RANK() OVER (...)` columns (value/market × positional/overall), a `RankedLeaderboardRow` projection interface, a new `GET /api/scoring/leaderboard/ranked` endpoint, and a real-Postgres test pinning RANK-skip semantics, the null-ADP guard, and rank-then-filter.

**Chunk 1 — RFC 9457 error handling**

1. **`@RestControllerAdvice` — centralized fault barrier.** One class with `@ExceptionHandler` methods applies across every controller. `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody` (same relationship as `@Controller`/`@RestController`). Spring's `ExceptionHandlerExceptionResolver` picks the *most specific* handler for the thrown type. This is the web-layer version of the Phase 0 exception translation (wrapping `IOException` into `ClaudeApiException`) — translate any failure into a consistent client-facing fault at one boundary instead of scattering try/catch. Camel analogue: a global `onException` for the whole route set.
   - **Interview line:** *"`@RestControllerAdvice` centralizes exception handling across all controllers. Spring picks the most specific `@ExceptionHandler` for the thrown exception, and an explicit handler takes precedence over a `@ResponseStatus` on the exception itself."*

2. **`ProblemDetail` (RFC 9457) — the standardized error contract.** Spring 6 / Boot 3 ship `org.springframework.http.ProblemDetail`, implementing RFC 9457 ("Problem Details for HTTP APIs"). Standard fields `type` / `title` / `status` / `detail` / `instance` plus an open properties map, served as `application/problem+json`. Returning a `ProblemDetail` directly (not wrapped in `ResponseEntity`) sets the HTTP status from its `status` field and the content type automatically — confirmed live: `Content-Type: application/problem+json` with no manual wiring. Same instinct as `VIA_DTO` and ActiveMQ message schemas — don't leak an unstable internal shape into a published contract, except here it's a documented *standard* a custom record can't claim.
   - **Interview line:** *"`ProblemDetail` is Spring 6's implementation of RFC 9457 — a standardized error body served as `application/problem+json`. I use it instead of a custom error record so the API speaks a documented standard clients and tooling already understand."*

3. **Extending `ResponseEntityExceptionHandler` — inherit framework-exception handling + precedence safety.** The base class already has `@ExceptionHandler` methods for the Spring MVC exception family (malformed body, wrong method, type mismatch, unknown URL), rendered as `ProblemDetail` in Boot 3. Extending it means I write handlers only for my exceptions plus any framework one I want to customize. Crucially, it removes a footgun: a broad `@ExceptionHandler(Exception.class)` catch-all would otherwise swallow framework 404s/405s into 500s — but with the base class present, those have *more specific* inherited handlers, so the catch-all only catches the genuinely unexpected. Verified: `format=BOGUS` hit my explicit `handleTypeMismatch` (title "Invalid Parameter", 400), proving most-specific won over both the inherited handler and the catch-all.
   - **Interview line:** *"I extend `ResponseEntityExceptionHandler` so my advice inherits ProblemDetail handling for all standard Spring MVC exceptions and I override only what I care about. It also means a catch-all `Exception` handler can't mask a framework 4xx into a 500, because those have more specific inherited handlers."*

4. **Bean Validation — the starter, `@Validated` vs `@Valid`, and the two exceptions.** Three things: (a) `spring-boot-starter-validation` is a *separate* dependency since Boot 2.3 — without it, `@Min` silently does nothing (classic "my annotations are inert" gotcha). (b) `@Validated` (Spring) on the *controller class* switches on method-parameter validation via an AOP proxy, for constraints on `@RequestParam`; `@Valid` (Jakarta) on a `@RequestBody` object cascade-validates its fields — different annotations, different jobs. (c) The interview-grade detail: validation surfaces **two** exception types by constraint location. `@Valid` on a body → `MethodArgumentNotValidException` (a Spring MVC exception, handled by the base class → 400). `@Min`/`@Max` on params under `@Validated` → `jakarta.validation.ConstraintViolationException` (NOT a Spring MVC exception → defaults to 500 unless I write a handler). My `page`/`size` case is the second one, so it gets an explicit handler producing a structured 400.
   - **Interview line:** *"Bean Validation surfaces two exceptions. `@Valid` on a request body throws `MethodArgumentNotValidException`, handled as 400 out of the box. `@Min`/`@Max` on method params under `@Validated` throw `ConstraintViolationException`, which isn't a Spring MVC exception, so it's 500 unless I handle it explicitly."*

5. **Reject vs clamp for `size` (design decision).** Switched the leaderboard from silently clamping `size` to `MAX_PAGE_SIZE` → rejecting oversized requests with `@Max` + 400. A published contract should make "you exceeded the limit" explicit rather than quietly returning less than asked — especially when the caller is another service or an LLM tool that can't see the coercion. This changed behavior, so the Day 4 `ArgumentCaptor` clamp test had to flip to assert reject-with-400 + repo never called — which is *why* that test existed (the suite is in place so polish can refactor safely). The shift from "captor proves the clamp" to "`verify(never())` proves the guard short-circuits" is the same behavior-not-state lesson from Day 5: validation fires before the method body, so there's no clamped value to capture — instead assert the collaborator was never reached.
   - **Interview line:** *"For an optional `size` param I reject oversized requests with `@Max` and a 400 rather than silently clamping. A published contract should make exceeding the limit explicit, especially when the caller is a service or an LLM tool that can't see the coercion happened."*

6. **Never leak internal exception detail.** The catch-all logs the real cause (`log.error("Unhandled exception", ex)`) but returns a *generic* message — never `ex.getMessage()` to the client, which can expose stack internals, SQL fragments, class names. Log the cause, return a generic body. Security habit interviewers probe for.

**Chunk 2 — active-player filter (and the "tested but dormant" lesson)**

7. **Reused the null-or-match guard, with one new wrinkle.** `AND (:active IS NULL OR p.active = :active)` on the already-joined `Player`, mirrored into the count query (and the join it depends on) so `totalElements` doesn't desync. The wrinkle: the param must be a boxed `Boolean`, not primitive — the guard needs null to mean "filter off," and a primitive `boolean` can't be null, so it'd default to `false` and silently filter to inactive-only. Bad value (`active=garbage`) → 400 via the chunk-1 `handleTypeMismatch` (the chunks compounding — error handling picked it up for free).
   - **Interview line:** *"An optional boolean filter has to be a boxed `Boolean`, not primitive — the null-or-match guard needs null to mean 'filter off,' and a primitive can't be null, so it'd default to false and silently filter to the wrong set."*

8. **Keep a tested-but-dormant filter (the YAGNI distinction).** Every player in my data is currently active, so the filter changes no live count (verified: 2022 unfiltered = 2022 active = 3216, and I checked the DB directly to confirm zero inactive scored players — *not* inferred from equal counts). I kept it anyway. The filter is a property of the API *contract*; the empty result set is a property of *today's data* — different lifecycles. It's verified by a `@DataJpaTest` that seeds an explicitly inactive row, so it's proven independent of the live data, and it's inert when the param is absent, so it costs nothing. YAGNI is about not building *speculative* features — not deleting *built-and-tested* behavior whose target set is temporarily empty. Same judgment as Day 1 rejecting a source discriminator (speculative) while keeping what the year discriminator earned — opposite direction, same line. Removing it would couple a stable contract to a transient data state and force a breaking change when ingestion later captures inactive players (a Phase 5 / data-completeness item).
   - **Interview line:** *"I kept the active filter even though every player in my data is currently active. The filter is a property of the API contract; the empty result set is a property of today's data — different lifecycles. It's tested against an explicitly inactive row, so it's verified independent of the live data, and inert when the param is absent. YAGNI is about not building speculative features, not deleting built-and-tested behavior whose target set is temporarily empty."*
   - **Same trap as Day 3 named:** "200 OK with an unchanged count" had two explanations — a working filter on retiree-free data, or a dead WHERE clause. Disambiguated by inspecting the actual data, not trusting the surface signal. Structural success ≠ semantic correctness. The repo test (seeding an inactive row) is now the *only* place the active guard is exercised against a true inactive case, because production data can't exercise it — which is the argument for why that test matters.

**Chunk 3 — window-function rank overlay (the deep one)**

9. **Window functions: aggregate without collapsing.** `GROUP BY` collapses N rows into one per group — you lose the individual rows. A window function computes the same kind of aggregate over a partition but *keeps every row*, attaching the result to each. `OVER (...)` is what turns an ordinary aggregate into a window function. `PARTITION BY` is the window analogue of `GROUP BY` but *without collapsing*; the `ORDER BY` *inside* `OVER` is internal to the window and independent of the query's final `ORDER BY`.
   - **Interview line:** *"`GROUP BY` collapses rows into one per group and you lose the individual rows. A window function computes the same aggregate over a partition but keeps every row, attaching the result to each. The `OVER` clause defines the window — without it `COUNT(*)` collapses; with `OVER (PARTITION BY x)` it annotates."*

10. **`RANK` vs `DENSE_RANK` vs `ROW_NUMBER` — the tie question.** For a three-way tie at rank 1 then the next value: `ROW_NUMBER` → 1,2,3,4 (never ties, breaks arbitrarily); `RANK` → 1,1,1,4 (ties share, then *skips* — "Olympic"); `DENSE_RANK` → 1,1,1,2 (ties share, *no* skip). Chose `RANK` for both value and market rank: two players tied for RB5 means the next is RB7, because two players are ahead of him, not one tier.
    - **Interview line:** *"`ROW_NUMBER` always assigns distinct numbers. `RANK` lets ties share then skips — three-way tie at 1, next is 4. `DENSE_RANK` shares then doesn't skip — next is 2. For a leaderboard where ties mean genuinely-equal standing, `RANK` is correct: two players tied for 5th means the next is 7th."*

11. **JPQL has no window-function grammar → forced to native SQL.** No `OVER` / `PARTITION BY` in JPQL. So `RANK() OVER (...)` requires `@Query(nativeQuery = true)` — raw Postgres SQL, table/column names not entity/field names. Consequence: no JPQL constructor expression (`SELECT new ...` is JPQL-only), so I map with a **Spring Data projection interface** instead — getters whose names match the column aliases, Spring generates the implementing proxy. The native-query counterpart to a constructor expression; binding is name-based (snake_case alias → camelCase getter), so aliases and getters must line up exactly (same discipline as the extracted `StatLine` interface). Nullable columns force boxed return types: market ranks are null for undrafted players → `Integer`, not `int`.
    - **Interview line (native forcing function):** *"JPQL has no grammar for window functions — no `OVER` or `PARTITION BY`. So a `RANK() OVER` query has to be `nativeQuery = true`. The tradeoff: I lose JPQL portability and can't use a constructor expression, so I map the result with a Spring Data projection interface."*
    - **Interview line (projection):** *"For native-query results I map with a projection interface — getters matching the column aliases, Spring generates the proxy. It's the native-query analogue of a JPQL constructor expression, but bound by name instead of constructor position, so the SQL aliases and the getters have to line up exactly."*

12. **The null-ADP trap, window-function form.** `RANK() OVER (ORDER BY adp ASC)` ranks null-ADP rows too — Postgres sorts `NULLS LAST` by default, so they'd get the *highest* ranks (250, 251...). An undrafted player should have *no* market rank, not "RB 47th." Wrapped the rank in `CASE WHEN adp IS NOT NULL THEN RANK() OVER (... ASC NULLS LAST) END` — the real players still rank 1..N correctly because nulls sort last and never displace them, and the CASE blanks the null rows to null. Wrote `NULLS LAST` explicitly even though it's the Postgres default — documents intent. Same trap as Day 4 (null value masquerading as a rank), new mechanics (`NULLS FIRST/LAST` is itself an interview-worthy Postgres detail).
    - **Interview line:** *"`RANK() OVER (ORDER BY adp ASC)` assigns ranks to null-ADP rows — Postgres sorts nulls last by default, so they'd get the highest ranks. An undrafted player should have no market rank, so I wrap the rank in `CASE WHEN adp IS NOT NULL` to null it out. The real players still rank 1..N because nulls sort last and never displace them."*

13. **LEFT JOIN so unprojected players survive.** `player_scoring` has every scored player; `player_projections` only has current-season rows for players with projections. An INNER JOIN would silently drop a scored player without a projection. LEFT JOIN keeps every scoring row, ADP where it exists, null where it doesn't. Join on the full composite key `(player_id, year)` so ADP matches the right season. For a historical season there are no current projections, so every ADP is null and every market rank is null — correct, because there's no "market" for a past draft. Market rank is inherently current-season; the LEFT JOIN makes that fall out with no special case.
    - **Interview line:** *"I LEFT JOIN projections onto scoring rather than inner-joining, because a scored player without a projection should still appear with a null ADP and null market rank — an inner join would drop him. The join is on the full composite key, so ADP is matched to the right season."*

14. **Rank-then-filter, not filter-then-rank (design decision + a hard SQL rule).** Decided overall rank must mean "standing among ALL players," not "among the filtered subset" — a rank shouldn't change meaning based on the view (filter to RB and the top RB is still overall #24, not #1). This forced a structure: **population filters (`season`, `format`) go in the CTE — they define what "all players" means; view filters (`position`, `active`) go in the outer query — they decide what to show.** Get this wrong and ranks are subtly meaningless (format outside → ranks a player's STANDARD vs PPR scores against each other; position inside → degenerate overall=positional). And a hard rule made the two-CTE shape mandatory: **window functions evaluate *after* `WHERE` in SQL's logical order, so you can't filter on a window result in the same SELECT** — the alias doesn't exist yet at filter time. Computing ranks in a CTE materializes them as columns the outer `WHERE` can see. (Same rule is why `WHERE value_rank_overall <= 50` for "top 50 overall" needs an outer query too.)
    - **Interview line (population vs view):** *"I rank over the full field then filter the view, so overall rank reflects standing among everyone, not the subset the caller filtered to. The window runs in a CTE constrained only by season and format — the population being ranked — and the position/active filters apply in the outer query. Population filters before the window, view filters after."*
    - **Interview line (evaluation order):** *"Window functions evaluate after the WHERE clause in SQL's logical order, so you can't filter on a window result in the same SELECT — the alias doesn't exist yet. To filter or further-rank on a window result you compute it in a CTE, which materializes it as a column the outer WHERE can see."*

15. **The overlay's actual product: the value-vs-market gap.** Four ranks per player (value/market × positional/overall). The gap between value rank and market rank — "the engine values him RB5, the market drafts him RB15" — is the draft edge. Live data confirmed it behaves the way draft theory predicts: the *top* of every position is efficiently priced (value rank ≈ market rank for the elite), and disagreement *opens up at the edges* (Derrick Henry: value RB5, market RB15) — which is exactly where exploitable draft value lives. This is the Phase 4 LLM signal, now computable.

16. **Count query needs no CTEs.** Ranks annotate rows, they don't change the row *count* — so the count query is a plain `COUNT(*)` over the same join + filters, no window functions, no CTE. Must carry the same `position`/`active` guards (and the join they need) or `totalElements` desyncs. Computing all those ranks just to count them would be wasted work.
    - **Interview line:** *"For a paginated window-function query, the count query is a plain `COUNT(*)` over the base join and filters, not the ranking query — ranks annotate rows, they don't change the count, so counting them is wasted work. The count still repeats the filters and join or the total desyncs from the content."*

17. **Two String params for one concept.** `format` (e.g. `STANDARD_6PT`) selects which scoring rows to rank; `adpBucket` (e.g. `STANDARD`) selects which per-format ADP column to rank market position over. Related but not identical — 4PT and 6PT share one ADP bucket (`ScoringFormat.adpBucket()`). Both passed as `String` because native SQL has no enum awareness; the controller takes typed enums (free 400 on bad input via chunk-1 handler) and derives `format.name()` + `format.adpBucket().name()`. Enum at the boundary, string at the column — mandatory here (JPQL could convert; native can't), not stylistic.
    - **Interview line:** *"I pass the scoring format and the ADP bucket as separate parameters because they answer different questions — the format selects which scoring rows to rank, the bucket selects which per-format ADP column to rank market position over. A 6-point and a 4-point format share one ADP bucket, so they're related but not the same value."*

**The window-function test (real Postgres, hand-calculated)**
- Tiny deliberate seed (5 players: 2 QB, 3 RB) with a **points tie** (two RBs at 300) and an **undrafted player** (no projection row). Every expected rank hand-calculable.
- **Load-bearing assertions:** (a) undrafted player's overall value rank = **5** after a two-way tie at 3 — pins `RANK` skip semantics (would be 4 for `DENSE_RANK`, and `ROW_NUMBER` would break the tie). One assertion guards the function choice. (b) undrafted player's market ranks **null**, value rank real — pins the null-ADP guard + LEFT JOIN. (c) top RB's overall value rank = **3** under a position filter — pins rank-then-filter (would be 1 if filter-then-rank regressed).
- **Why real Postgres, not H2:** `RANK`'s tie-and-skip and `NULLS LAST` semantics differ between engines — an H2 test can pass with *wrong numbers*. The strongest case in the whole suite for why H2 lies. `@AutoConfigureTestDatabase(replace = NONE)` + the `pgvector/pgvector:pg16` singleton container.
    - **Interview line:** *"I test window-function queries on real Postgres, never H2, because RANK's tie-and-skip and NULLS handling differ between engines — an H2 test can pass with wrong numbers. My seed is tiny and includes a tie and an undrafted player so every expected rank is hand-calculable, which lets one assertion — an overall rank of 5 after a two-way tie at 3 — pin that I'm getting RANK and not DENSE_RANK or ROW_NUMBER."*

**Two questions I raised that map onto the architecture (VORP / the Phase 3–4 hook)**
- Asked: shouldn't overall (cross-positional) rank matter, and how do we handle a tier *cliff* (RB1=300, RB2=299, RB3=297, **RB4=240**)? Both are the door to **VORP — Value Over Replacement Player**.
- **Overall rank is now** (free — `RANK() OVER (ORDER BY points DESC)`, just omit the partition). But raw overall rank by points is a *trap*: a QB's 380 isn't 80 points better than an RB's 300, because replacement-level QBs also score high. Positions only become comparable via points-over-replacement.
- **VORP / tier cliffs are Phase 3–4.** VORP = projected points minus the last *startable* player at the position (replacement level, derived from `LeagueSettings` roster slots + league size). It simultaneously makes positions comparable on one scale *and* exposes cliffs (a steep dropoff shows as a VORP collapse). The architecture fork: **VORP itself is deterministic math → it belongs in the Java engine** (same Day 2 boundary — arithmetic lives in the engine). **Tier *cliffs* are a judgment call** ("is a 12-point gap a cliff or noise?") → that's where the LLM reasons over VORP-annotated data. Same line all project: providers predict, Java scores/ranks/values, the LLM strategizes. Today's overlay already carries `total_points` and ADP, so VORP needs no new data plumbing later — substrate laid, not precluded.
    - **Interview line (domain fluency):** *"Raw rank treats adjacent players as equal-distance, but draft value is points over replacement, not ordinal position. VORP — projected points minus the last startable player at a position — makes positions comparable on one scale and exposes tier cliffs, because a steep dropoff shows as a collapse in value over replacement. That's why a lower-scoring RB can be a better pick than a higher-scoring QB."*

**Live-data verification (chunk 3)**
- 2026 all positions: value ranks track points; the top 5 are all QBs (6pt passing TD inflating QBs — real, not a bug); Josh Allen value rank 1 / market rank 31 — the engine-vs-market gap visible immediately.
- 2026 RB filter: top RB Bijan value-rank-position 1 but value-rank-overall 24 — rank-then-filter confirmed empirically (would be 1 if broken). `totalElements` dropped to 674 — count query carried the filter.
- 2022 historical: all `adp`/market ranks null, value ranks populated — LEFT JOIN + null guard both working in one response.

**Mistakes / things I learned**
- `jq` not installed in Git Bash (MINGW64 has no `apt`; that was a WSL command). Used `grep -o '"totalElements":[0-9]*'` instead — fine for these checks.
- Reading "filtered count == unfiltered count" as "filter works" would have been the Day 3 trap; checked the DB directly instead.

**Production items still deferred (noted, not blocking)**
- Active filter is architecturally correct but operationally dormant until ingestion captures inactive (departed) players — Phase 5 / data-completeness.
- VORP, tier detection, cross-positional draft pressure — Phase 3–4 (engine computes VORP, LLM reasons about cliffs).

### Project structure (Week 5 Day 1)
```
read-option/src/main/java/app/readoption/
├── error/
│   └── GlobalExceptionHandler.java          ← NEW (@RestControllerAdvice extends ResponseEntityExceptionHandler, ProblemDetail)
├── playerscoring/
│   ├── PlayerScoringController.java          ← @Validated, @Min/@Max, + GET /leaderboard/ranked
│   ├── PlayerScoringRepository.java          ← + findRankedLeaderboard (native, 3-CTE window query); active guard on findLeaderboard
│   └── RankedLeaderboardRow.java             ← NEW (projection interface)
└── (pom.xml)                                 ← + spring-boot-starter-validation

read-option/src/test/java/app/readoption/
└── playerscoring/
    └── PlayerScoringRankedRepositoryTest.java ← NEW (@DataJpaTest, RANK skip / null-ADP / rank-then-filter on real Postgres)
```

---

### Week 3 Status
- [x] Day 1 — Project setup, Docker Compose, Flyway V1, Player entity + repository
- [x] Day 2 — Sleeper API integration, PlayerSyncService, ETL pipeline (3,217 players)
- [x] Day 3 — Player stats: schema, entities, JPA relationships, stats sync (5 seasons, ~15k stat lines)
- [x] Day 4 — Fantasy scoring engine: 5 architectural decisions, ScoringService, StatLine interface, Position enum, first unit tests, V3 migration (fumbles_lost)

### Week 4 Status
- [x] Day 1 — `player_scoring` table (Flyway V4), entity with Lombok, scoring pipeline wired into stats sync, recompute endpoint, Lombok refactor across all entities
- [x] Day 2 — `player_projections` table (Flyway V5), projection DTOs, `fetchProjections`, sync pipeline, controller; loaded 2026 rotowire projections
- [x] Day 3 — `Scorable` interface, config-driven source routing, projection scoring validated against rotowire `pts_*`
- [x] Day 4 — Query/read endpoints: leaderboard (pagination + VIA_DTO + position filter), player profile (composite DTO: history + projection + positional rank), `String`→enum migration on `scoringFormat`
- [x] Day 5 — Test suite: four slice patterns (plain unit, Mockito service, `@WebMvcTest`, `@DataJpaTest`+Testcontainers), `TestFixtures` factory, `AbstractPostgresTest` singleton container, whole-app risk-based coverage via Claude Code under review

### Week 5 Status
- [x] Day 1 — Read-API polish: RFC 9457 error handling (`@RestControllerAdvice` + `ProblemDetail`), `@Min`/`@Max` pagination validation, active-player filter, market-rank window-function overlay (native query + projection interface + real-Postgres test). **Phase 1 closed.**
- [x] Day 2 — Phase 2 collection layer: `player_projection_raw` (V6, no-FK landing table), player-id mapping (`espn_id` enrichment from DynastyProcess crosswalk, bulk `@Modifying` update), verified ESPN feeder (multiplexed-array selector, numeric stat-id map pinned empirically — INT=20 / FUM=72, two-pt null), upstream 502 handling. Two sources landing in `player_projection_raw`; reconciliation next.

---

## Phase 1 — COMPLETE ✅

Data foundation done: ETL pipeline (players, 6 seasons of stats, current-season projections), deterministic scoring engine (6 formats), computed scoring table, read API (player profile + leaderboard + ranked leaderboard with window-function value/market overlay), RFC 9457 error handling, pagination validation, and a risk-based test suite (4 slice patterns, real-Postgres custom-SQL coverage).

**Hours invested:** ~72h

Next: Phase 2 — projections aggregator (first real LLM use: multi-source reconciliation).

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
- FK on external-source tables (stats, projections) vs no-FK on computed tables (scoring) — referential integrity where rows reference real entities
- Capturing provider provenance: `source` from the payload's `company` field (Sleeper aggregates rotowire), not hardcoded — foundation for multi-source reconciliation
- Per-format ADP columns and the `999` "unranked" sentinel → NULL at the ETL layer
- Cleansing a misleading source field: rotowire's `gp = 18` (schedule weeks) overridden to 17 (games played) for comparable PPG
- Provider-computed points as a cross-check oracle, never a source of truth — scoring conventions differ across providers (−1 vs −2 per INT)
- Structural success vs semantic correctness: a wrong-endpoint bug between same-schema endpoints passes compile/deserialize/status — only the data is wrong
- `BigDecimal.valueOf(double)` vs `new BigDecimal(double)` for decimal columns like ADP

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
- Jackson boolean-getter leak: `@JsonIgnore` on the `isNew` field doesn't suppress the `isNew()` getter (field property "isNew" vs getter property "new") — annotate the method
- When the response is a single entity (not composite), returning the entity is on-policy; the DTO trigger is the response shape diverging from any single entity

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
- The scoring/LLM boundary: deterministic scoring (6pt passing TD, TE premium) lives in the engine; the LLM reasons about strategy — league-specific value vs market ADP, scarcity, roster construction
- Provider scoring conventions differ; the engine encodes the league's rules, not the provider's
- Parameterized `ScoringRules` value object as the scalable path for arbitrary formats (Phase 3) — the enum graduates into a registry of presets, position passed into scoring for TE premium
- Not auto-triggering scoring across data sources: scoring routes by source (projections vs actuals), so the stats-sync trigger doesn't transfer to the projection sync
- `Scorable extends StatLine` (Interface Segregation): `StatLine` stays pure for the formula and unit tests; `Scorable` adds player identity for the persistence loop — one scoring loop over `List<? extends Scorable>`
- Config-driven routing over data-presence: a provider returns zero-stat rows for an unplayed season (present but empty), so an explicit current-season boundary beats inferring source from data shape
- Provider points as a regression oracle: the cross-check caught a real bug — the engine was scoring −1 per INT while the league rule is −2; fixed the constant and recomputed, after which QB scoring intentionally diverges from rotowire's −1 numbers by the interception count, while non-INT lines still match to the cent
- HHH000502 on re-sync: `merge()` sees a read-only `@ManyToOne` set to null and warns; dropping the unused association silences it while the database FK still enforces integrity (dropping the mapping ≠ dropping the constraint)
- `@Transactional` REQUIRED propagation: the sync's cross-bean call to scoring joins the sync's transaction — save and score commit or roll back together

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

**Pagination / Read APIs (Week 4 Day 4)**
- A REST endpoint is a published contract consumed by anything — service, test, or LLM tool call; it doesn't know its caller (same decoupling as a Camel route or DB view), so building the API before any UI is normal
- Pagination is bounded reads — the context-bomb lesson at the API layer; `MAX_PAGE_SIZE` clamp protects payload, memory, and (since an LLM is a downstream consumer) context cost
- `Page` vs `Slice` — count query vs over-fetch-one; "3 of 13" vs infinite scroll
- Don't return `Page` raw — `PageImpl` is internal with no JSON-stability guarantee; `@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)` gives a stable `PagedModel` envelope (warning since Spring Data 3.1, fix in 3.3)
- Bake the sort into a leaderboard query; don't expose arbitrary `Sort` (non-existent property = 500, unindexed sort)
- Hibernate 6 ad-hoc entity joins (`JOIN X ON ...`) between unmapped entities — keeps computed tables FK/association-free yet joinable in HQL
- JPQL constructor expressions project straight into DTOs — no hydration, no lazy proxies; records as targets
- Explicit `countQuery` for projection+join queries; a filter on a joined table forces the join into the count query, or totals desync
- Composite/assembled DTOs in a service when the response spans multiple sources/rows; interface projections only for a flat single-query slice; the year discriminator splits history from projection for free (Day 1 paying rent)
- DTOs decouple the API contract from the schema and keep internal fields (`isNew`/`getId`, audit cols, lazy proxies) off the wire — `@JsonIgnore` was the symptom, DTOs the cure; trigger is divergence from a single entity
- Default for an optional param at the controller via `${prop}` placeholder in `defaultValue`; service stays explicit; same config property can feed a controller default and business routing without being duplication
- Controller→repository for a single read; introduce a service only when it holds logic
- `@Enumerated(EnumType.STRING)` over `ORDINAL`; migrating a `String` column to an enum needs no data change when it already stored names; retyping a composite-key field touches the IdClass too (the upsert/equals/hashCode machinery)
- Optional filter via null-or-match guard → Criteria/Specifications when filters multiply; enum at the boundary (free 400), string at the column
- `@ResponseStatus` maps a custom exception to a status (404); `@ControllerAdvice` is the upgrade for structured error bodies
- Positional rank is derived (count of better ADP + 1), not stored; the null-value-ranks-#1 trap (guarded at the service); searched `CASE` picks the per-format ADP column; market-rank vs value-rank gap is the Phase 4 draft signal; window function for ranking everyone at once

**Testing (Week 4 Day 5)**
- Risk-based coverage, not uniform: test domain logic + custom SQL + web wiring; skip Lombok getters, record DTOs, inherited Spring Data CRUD, ID-class equals, framework behavior
- Test slices load one layer: `@WebMvcTest` (web), `@DataJpaTest` (JPA), vs `@SpringBootTest` (whole context) — faster, failures localize
- Mockito service tests with `@ExtendWith(MockitoExtension.class)` + `@Mock`, no Spring; build-by-hand over `@InjectMocks` when the constructor mixes mocks with scalars
- `verify(mock, never())` and `ArgumentCaptor` verify *behavior/interaction* (the guard didn't fire, the clamped value crossed the boundary), not just return values
- Strict stubs (Mockito default) fail on unused stubs — each test stubs only its real path; `lenient()` to opt out (e.g. a shared `doAnswer` capture)
- `@WebMvcTest` + `MockMvc` + `jsonPath`; mock every controller collaborator with `@MockitoBean` (not the Boot-3.4-deprecated `@MockBean`); 400 from bad param binding, 404 from a `@ResponseStatus` exception, `@Import` the page-serialization config to assert the `VIA_DTO` envelope
- `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers (production `pgvector/pgvector:pg16` image, `@ServiceConnection`, singleton container) — runs custom SQL on the real engine; Flyway builds the schema and Hibernate validates, so migrations + mappings are proven too; transactional rollback isolates tests
- H2-vs-Postgres: custom SQL (entity joins, `CASE`, null semantics) can pass on H2 and fail on Postgres — only the real engine test is trustworthy
- Fixture factory centralizes valid entities because tests bypass the ingestion layer; common case with defaults, edge values inline; `not-null property references a null or transient value` is Hibernate's entity check before INSERT
- Delegation workflow: own one exemplar of each test shape by hand, hand the repetition to AI tooling with templates + a do-not-test list, review each on a red/green bar (close-read ETL cleansing), full-suite run to prove isolation


---

## Phase 2 — Projections Aggregator (IN PROGRESS)

> Collection layer ✅ complete (two sources landing in `player_projection_raw`). Reconciliation layer next.

### Week 5 Day 2 — Collection Layer: Multi-Source Projections (ESPN feeder + player-id mapping)
**Time:** ~6h

**What I built**
- **V6 migration**
  - `player_projection_raw` — per-source projection **landing table**. PK `(player_id, year, source)` (source in the key = the staging grain). **No FK** to `player` (landing table; integrity enforced at the transform step, not on ingest — mirrors the `player_scoring` no-FK decision). Single `adp` + `adp_format` columns (ESPN gives one ADP, not per-format). `source_payload JSONB` for audit/replay.
  - `player.espn_id` column + `idx_player_espn_id` (the ESPN resolution hot path).
- **Entities:** `PlayerProjectionRaw` (Persistable upsert, `@Builder.Default isNew`, `@JsonIgnore` on `getId()`/`isNew()`, `Scorable`, `@JdbcTypeCode(SqlTypes.JSON)` on the JSONB field) + `PlayerProjectionRawId` (3-field IdClass).
- **`PlayerIdMappingService`** — enriches `player.espn_id` from the DynastyProcess `db_playerids.csv` (bundled snapshot in `resources/playerids/`). Streams the CSV with commons-csv, matches only players already in the DB, bulk `@Modifying` JPQL update with an idempotency guard.
- **`PlayerDataSyncService`** — orchestrator running player sync → id mapping as **two independent transactions** (not nested). Endpoints `/sync-all` (both stages) and `/sync-espn-ids` (mapping only, the dev loop). Original `/sync` (Sleeper-only) kept unchanged.
- **ESPN feeder stack:** `EspnClient` (Spring `RestClient`, `X-Fantasy-Filter` header), `EspnPlayersResponse` DTOs (`@JsonIgnoreProperties(ignoreUnknown=true)`, `Map<String,Double>` for the numeric-keyed stat map), `EspnStatId` (verified id map), `EspnProjectionMapper` (season-entry selector + stat mapping + ADP + JSONB serialize), `EspnProjectionSyncService` (resolve `espn_id → player_id`, land rows, three-outcome report). Endpoint `/sync/espn` (season-pinned from config).
- **Error handling:** `EspnUnavailableException` (wraps `RestClientException`) → `GlobalExceptionHandler` → **502 Bad Gateway** (upstream failed, not us), RFC 9457 `ProblemDetail` matching the existing `problems/` URI convention.

**Verified data landed**
- ESPN feeder: `fetched=400, landed=347, unresolved=39, no-projection=14`. Of 39 unresolved: 32 unmodeled D/ST + kickers + a fullback + 3 rookies that postdate the crosswalk snapshot. **Zero startable skill players.**
- Gibbs (`9221`): rushing 1373, receptions 68, receiving 547, rec TD 3, small `fumbles_lost`, `two_pt_conv` null, `adp_format='PPR'` — matches the ESPN payload exactly.
- Mahomes (`4046`): passing 3993, pass TD 26, INT 12, fumbles 3 — confirms the two hardest-won ids (`20`, `72`).

**What I learned — concepts**

- **Landing table → consensus mart (staging→transform→mart).** Raw per-source rows land in `player_projection_raw`; the existing `player_projections` becomes the reconciled mart. Downstream scoring/ranking never changes — multi-source complexity is contained in the reconciliation step.
  - **Interview line:** *"I kept raw per-source projections in a landing table and the reconciled consensus in a separate mart — staging-and-transform, the same pattern as any ETL pipeline. The payoff is the downstream scoring layer never changed: it still reads one consensus row per player."*

- **Entity resolution / ID crosswalk is MDM.** Each provider keys players by its own id, so multi-source ingestion is an entity-resolution problem before it's a reconciliation problem. Solved with a deterministic crosswalk (DynastyProcess `db_playerids`) enriching `player.espn_id`, a logged review queue for the residual, and **no fuzzy matching**.
  - **Interview line:** *"Each provider keys players differently, so multi-source ingestion is entity resolution before reconciliation. I persist a deterministic crosswalk to one canonical id rather than fuzzy-match on name — master-data management, one golden record, many source keys — with a logged review queue for the residual."*

- **Verify the field on a real record before committing schema.** My locked "join on Sleeper's `espn_id`" decision was falsified: `espn_id` was null for 44% of skill players, including a top-2 pick (Gibbs). The DynastyProcess crosswalk resolved him correctly and covered 95% of the draftable pool.
  - **Interview line:** *"I had a locked decision to join on a shared id one provider publishes. A single curl showed it null for 44% of players including a star — a 'done' decision became open again. Verifying external data shape before schema is the cheapest insurance in a pipeline."*

- **Denominator discipline.** A null-`espn_id` count looked alarming and dissolved as the filter tightened toward relevance: 1136 raw → 501 by position → **0** when gated by projected points. The scary number was real; its relevance was not.
  - **Interview line:** *"I never trust a null-count in isolation — I tighten the filter toward the population that matters until the number holds up or dissolves. A 1,100-player gap went to zero once I gated by startable points. The count tells you how many; the breakdown tells you whether it matters."*

- **`@Modifying` bulk update bypasses the persistence context.** A `@Modifying` JPQL `UPDATE` runs as direct DML — no entity hydration, so `@PreUpdate` never fires. I set `updated_at = CURRENT_TIMESTAMP` inside the query by hand. Right tool for column enrichment over thousands of rows; the gotcha is stale managed entities (not triggered here — only ids were loaded).
  - **Interview line:** *"A @Modifying JPQL update is direct DML — it skips the persistence context, so lifecycle callbacks like @PreUpdate don't fire and managed entities can go stale. I use it for bulk enrichment and set the audit timestamp in the query by hand, because the callback that normally maintains it is bypassed."*

- **`@JdbcTypeCode(SqlTypes.JSON)` for JSONB.** Hibernate 6 maps arbitrary SQL types this way; for a Postgres `jsonb` column there's no native Java type, so without it Hibernate binds `String` as `varchar` and Postgres rejects it. Modern replacement for a hand-written `UserType` / hypersistence-utils.

- **A real CSV parser, not `split(",")`.** Free-text columns (`college`, `name`) contain quoted commas that silently misalign every later field; commons-csv handles quoting and lets me read by header name (survives column reordering).

- **Bundled snapshot (Option B) over runtime fetch.** A reference table that changes on the NFL calendar, not per request → commit the CSV, read from classpath. Hermetic tests (no network in CI), and "re-commit to refresh" is a reviewed, versioned change.

- **ESPN's multiplexed stats array.** One array per player carries every season × actual/projected × week × split, each a full ~50-key map (≈6,000 lines/player). The feeder's first job is deterministic **entry selection**: `seasonId==2026 && statSourceId==1 && scoringPeriodId==0` (proven unique per player). `appliedTotal` is ignored — we score the raw stats with our own engine.

- **Numeric stat-IDs → canonical fields, pinned empirically.** ESPN keys stats by integer ids (`24`=rush yds, `53`=rec, `42`=rec yds…). Confirmed ids by hand-scoring; pinned the undocumented ones by **triangulation / cross-reference against trusted data**: matched ESPN season-actuals against already-validated rotowire totals, intersecting candidate keys across three players. Found `INTERCEPTIONS=20` (Mahomes 3-INT game), `FUMBLES_LOST=72` (Fields/Nix/Dak intersection; decoy `73` was turnovers = fumbles+INTs, eliminated by arithmetic). `TWO_PT_CONV` left **null on purpose** — no single key or key-pair matched the known 5/4/3 totals across three probes (ESPN splits rush/pass 2pt; my canonical field is a combined total), and a wrong mapping silently corrupts scores.
  - **Interview line:** *"To pin an undocumented stat id I don't guess harder — I assert a known value and find the key that holds it, intersecting across players so coincidences cancel. A key reading 1 for a one-fumble back and 3 for a three-INT QB is turnovers, not fumbles. And I treated an empty match as signal: when no key or key-pair fit, the source tracks the stat more granularly than mine, so I leave it null rather than overfit — a measurable zero beats a confident wrong mapping."*

- **Exception strategy follows the unit of work.** Read endpoints throw → global handler → `ProblemDetail`. A batch sync over hundreds of players can't throw per row (one bad record kills the run), so per-row outcomes are **collected** into `EspnSyncResult` (landed / unresolved / no-projection). Only a **total upstream failure** is exceptional → `EspnUnavailableException` → **502** (not 500: my server didn't break, my dependency did). `ResponseEntityExceptionHandler` covers the inbound MVC family but not my own outbound `RestClientException`, so the explicit handler is required.
  - **Interview line:** *"The exception strategy follows the unit of work — a request, or a batch. Per-request errors throw and render as a ProblemDetail; per-row batch outcomes are accumulated into a result report; only a total upstream failure throws, and I map it to 502 so the status honestly says the dependency failed, not my code."*

- **A review queue is only trustworthy when every entry is explainable.** 39 unresolved = 32 unmodeled D/ST + kickers + a fullback + 3 rookies. Zero startable skill players. An *unexplained* name in the queue is the alarm, not the count.

- **NUMERIC over INTEGER for raw stat columns (decided).** Integer rounding (1372.59 → 1373) is fine for scoring one source, but injects noise into the cross-source **points-dispersion** signal reconciliation depends on. Raw stat columns become `NUMERIC` — carried forward as V7, the first step of reconciliation.

**Mistakes & lessons**
- **Built `EspnClient` on a different HTTP stack than `SleeperClient`** (RestClient vs hand-rolled `java.net.http.HttpClient`) because I was working from pasted fragments, not the repo. Lesson → adopt the **chat-designs / Claude-Code-executes** split: chat for the *why* and architecture, Claude Code for multi-file builds against the whole repo so conventions stay consistent. Standardize *forward* (migrate Sleeper to RestClient later), don't churn tested code mid-feature.
- **Predicted "a handful" unresolved; got 1099 (35%).** Wrong about the denominator until gated by points. The method, not the effort, fixed it.
- **`comm` gave a false 100%-missing** on Git Bash (locale sort + CR mismatch). Lesson: **self-test on a known value (Gibbs=1 in both files) before trusting a derived count**; switched to `grep -Fxv`.
- **First crosswalk decision (`espn_id` primary) was falsified** by the 44% null rate — caught by verifying the field on a real record before writing the migration.

**To revisit**
- **V7:** `ALTER player_projection_raw ... TYPE NUMERIC` for the stat columns (decided this session; first step of reconciliation).
- Migrate `SleeperClient` to `RestClient` (standardize forward on the framework idiom).
- `@Validated @ConfigurationProperties` for `readoption.espn.player-limit` (validate config at the startup boundary, not as a request param).
- Skip ESPN entries with negative ids / `defaultPositionId==16` (D/ST) before the resolve attempt → quieter review queue.
- Refresh the crosswalk snapshot post-draft to close the rookie gap.
- Tests for the Phase 2 collection components (mapper selector + stat mapping; sync services; the id-mapping enrichment).
- **Reconciliation (next):** land rotowire into `player_projection_raw`; score each source via `ScoringService`; coefficient-of-variation on points → route contested players to the first real Spring AI structured-output verdict call; write consensus to `player_projections`.

---

### Phase 2 — Concepts Cheat-Sheet (additions)

**Data Engineering / ETL**
- Landing table vs consensus mart: per-source rows (`source` in PK) staged in a raw table, reconciled into a single mart row; downstream consumers read the mart unchanged
- No FK on a landing table — integrity enforced at the transform step (resolve id, or route to a review queue), not by the DB on ingest; a FK would abort the batch on an unresolved row
- Entity resolution / crosswalk: deterministic id-to-id map (DynastyProcess `db_playerids`) over fuzzy matching; logged review queue for the residual — MDM, one golden record many source keys
- Verify external field shape on a real record before committing schema (Sleeper `espn_id` null for 44%); the locked decision was wrong until checked
- Denominator discipline: tighten the filter toward the relevant population before reading a data-quality number (1136 → 501 → 0)
- A review queue is only trustworthy when every entry is explainable; an unexplained name is the alarm, not the count
- Pinning undocumented source codes: assert a known value, find the key that holds it, intersect across multiple entities so coincidences cancel; cross-reference against an already-trusted source as ground truth
- Empty match = granularity-mismatch signal, not failure (ESPN splits rush/pass 2pt vs a combined canonical field) → sum components, or null a low-impact field rather than overfit
- Bundled reference snapshot (classpath) vs runtime fetch: hermetic tests + reviewed/versioned refresh for data that changes on a seasonal cadence
- A real CSV parser over `split(",")`: quoted commas in free-text columns silently misalign every later field; read by header name
- NUMERIC over INTEGER when small per-source differences feed a downstream dispersion signal — rounding noise corrupts the routing decision

**JPA / Hibernate**
- `@Modifying` JPQL `UPDATE` = direct DML: skips the persistence context, `@PreUpdate` doesn't fire (set `updated_at` in the query), managed entities can go stale (`clearAutomatically=true` if any were loaded)
- Idempotent bulk update via a guard clause (`WHERE espn_id IS NULL OR espn_id <> :v`) → re-run touches only genuinely-changed rows; returned count means "actually changed"
- `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition="jsonb"` for a Postgres JSONB column (no native Java type; replaces a hand-written `UserType`)
- Derived query (`findByEspnId`) for an indexed single-column lookup — no `@Query` needed
- Persistable upsert scoped per source: load existing keys for `(year, source)`, `markExisting()` so re-run UPDATEs instead of duplicating

**HTTP Clients / Spring**
- `RestClient` (Spring 6.1+, successor to `RestTemplate`): fluent `get().uri().header().retrieve().body(Type.class)`, deserialization via message converters; custom header (`X-Fantasy-Filter`) is one `.header()` call
- A provider taking its query as a JSON **header** (ESPN) instead of params; `limit` requires an accompanying `sort` — verified empirically, not from docs
- Two HTTP clients on different stacks (hand-rolled `java.net.http.HttpClient` vs `RestClient`) is a consistency smell in a CV codebase → standardize forward, not by churning tested code mid-feature
- `@JsonIgnoreProperties(ignoreUnknown=true)` on large third-party DTOs — model only consumed fields; deserialization survives the provider adding fields
- JSON keys that are *data* (ESPN numeric stat ids) → bind to `Map<String,Double>` and translate in code; a fixed DTO can't represent dynamic keys

**Error Handling**
- Exception strategy follows the unit of work: per-request → throw → `ProblemDetail`; per-row batch → collect outcomes into a result record; total upstream failure → throw
- Upstream dependency failure → **502 Bad Gateway**, not 500 — your server didn't break, the dependency did; returning 500 sends a debugger to the wrong codebase
- `ResponseEntityExceptionHandler` covers the inbound MVC exception family but **not** your own outbound `RestClientException` — wrap it in a domain exception and handle explicitly
- Request-param validation (`@Min`/`@Max` at the controller, untrusted input) vs config validation (`@Validated @ConfigurationProperties`, at startup) — validate at the boundary the input crosses

**Workflow / Tooling**
- Chat works from pasted snapshots; the repo is the truth. Once a codebase grows cross-cutting conventions, chat-as-builder becomes a source of inconsistency
- Split: **chat designs** (the why, architecture, interview framing — the CV-bearing understanding) / **Claude Code executes** (multi-file builds with whole-repo context so conventions hold) / **IntelliJ** (edit, run, psql-verify, read diffs)
- Keep owning the load-bearing logic and the review; delegate scaffolding — don't delegate the understanding

---

## Phase 2 (continued) — Reconciliation Layer

The first real LLM work in the project: projections from two providers (rotowire via Sleeper, ESPN) reconciled into a single consensus mart, with an LLM classifying the genuine disagreements. The headline lesson is at the very end, and it's the one I'll tell in interviews: the first verdict run **worked structurally and failed substantively** — it ran 128 model calls clean and flagged 94% of them as "uncertain" — and the fix was *retrieval*, not a cleverer prompt.

**What I did**
- **Migrations:** V7 (raw stat columns `INTEGER → NUMERIC(7,2)`), V8 (mart stat columns `→ NUMERIC(7,2)`), V9 (`player_projection_reconciliation` audit table, no FK).
- **Scoring contract:** widened `StatLine`'s getters from `Integer` to `Number` so genuinely-integer historical `PlayerStats` and now-`BigDecimal` projection entities both satisfy one contract via covariant returns; `ScoringService` narrows to `BigDecimal` internally.
- **Staging retrofit:** rotowire now lands in `player_projection_raw` (it used to write straight to the mart — the Phase 1 single-source shortcut). After this, **reconciliation is the only writer of `player_projections`.**
- **Option A engine:** `DispersionCalculator` + `ConsensusBuilder` (pure, no Spring), `VerdictClassifier` (`ChatClient` + `BeanOutputConverter<Verdict>`), `ReconciliationService` (phased READ → REASON → WRITE → RE-SCORE), `ReconciliationWriter` (separate `@Transactional` bean), config in `@Validated ReconcileProperties`.
- **Calibration:** a `?dryRun=true` mode returns the CV distribution; used it to set the threshold (0.10) against the real bimodal shape instead of guessing.
- **First verdict run:** 310 two-source players, 128 contested at 0.10 — **120 `FLAG_UNCERTAIN`, 7 `FAVOR_LOW`, 1 `TRUST_CONSENSUS`.**
- **Diagnosed it, built RAG increment 1:** `PriorSeasonContextRetriever` injects the player's last 3 seasons of actuals into the verdict prompt; moved the system-prompt honesty boundary to match.
- **Re-ran:** **53 flag / 48 favor-low / 22 favor-high / 5 trust** — a discriminating, bidirectional distribution, with rationales now citing the injected actuals.
- **Feeder parity:** `source_payload` populated on rotowire (scoped to the typed object mapped from), ESPN `games_played=17` and `team` from the resolved player, duplicate-`interceptions` cleanup.

**What I learned**

- **Grain = what one row means = the PK.** Stating the grain out loud before modelling is the discipline I already did implicitly when choosing keys. The landing table's grain is one row per source per player-season; the mart's is one row per player-season after reconciliation.
  - **Interview line:** *"Before I model a table I state its grain — what one row represents — and the primary key enforces it. The landing grain is one row per source per player-season; the mart grain is one row per player-season after reconciliation. Getting the grain wrong is how you double-count or silently drop rows downstream."*

- **A mart is the published, query-ready table; staging is the raw landing zone.** Adding the second source didn't add data, it forced a staging layer — and demoted the mart from a load target to a transform output. Phase 1 got away with loading straight to the mart only because one source meant nothing to reconcile.
  - **Interview line:** *"Adding the second source forced a staging layer and turned the mart into a transform output. Source feeds land raw, business rules produce the golden record, consumers read the clean mart — the same discipline as not loading raw feeds into a reporting mart."*

- **The grain dictates how every other per-format attribute is modelled.** Scoring format shows up three ways in this schema, each correct: points are *deepened into rows* (vary by format, queried by format), the stat line has *no format* (format-invariant), ADP is *widened into columns* (the row's grain left nowhere to deepen it).
  - **Interview line:** *"Format appears three ways and each is deliberate: points deepened into rows because they vary by format, the stat line format-less because stats are format-invariant, ADP widened into columns because the grain was already one-per-player-season. The grain decision dictates how the format-varying attributes around it get modelled."*

- **Option A — the LLM classifies, the engine applies the verdict as a selection rule over stat lines already in hand.** The model returns an enum (`TRUST_CONSENSUS` / `FAVOR_HIGH` / `FAVOR_LOW` / `FLAG_UNCERTAIN`); the engine writes a real source's line or a per-stat median. Every number in the mart traces to a real source or a median of real sources — the model never emits a stat or a point.
  - **Interview line:** *"The LLM classifies; it never computes. On a contested player it returns an enum, and the deterministic engine applies that as a selection rule over stat lines I already have. Every projected number traces back to a real source or a median of real sources, so the output stays reproducible and auditable even where a model was in the loop."*

- **Coefficient of variation, not raw spread, for the dispersion signal.** Variance scales with magnitude — a 10-point gap is noise on a 300-point QB and a fork on a 40-point TE — so I normalize by the mean to make disagreement comparable across the pool. Population std dev (I have all the sources, not a sample); at n=2 the CV collapses to `|a−b|/(a+b)` and the "median" is the midpoint, with real robustness arriving at n=3.
  - **Interview line:** *"I route on coefficient of variation, not raw spread, because variance scales with magnitude — a ten-point gap is noise on a 300-point quarterback and a real fork on a 40-point tight end. Normalizing by the mean makes one threshold mean the same thing everywhere."*

- **Guard the mean before dividing, and let the floor do double duty.** A points floor applied before the CV skips non-draftable players — which kills the μ→0 divide-by-zero *and* bounds the contested subset, which bounds the LLM call count, which bounds cost.

- **Calibrate the threshold against the real distribution, not a round number.** The dry-run showed a bimodal shape — a mass under 0.10 (sources agree), a pile at 0.20+ (sources see different players), a thin valley between. I set the threshold at the front of the valley so the genuinely-ambiguous middle gets judgment instead of a blind median.

- **The measuring stick is a *detector*, not an output format.** I score each source in one format only to measure spread, then throw the number away; the output is a format-invariant stat line. PPR dominates Standard as a detector because PPR points = Standard points + receptions, so Standard alone has a reception-shaped blind spot on contested target-share players.
  - **Interview line:** *"The format I measure disagreement in is a detector, not an output — I score in it only to measure spread, then discard the number. PPR sees every disagreement Standard sees plus reception disagreements, so it's the more sensitive detector; the reconciled stat line is format-invariant and serves every format downstream."*

- **`BeanOutputConverter` is the framework version of the Phase 0 hand-rolled JSON.** It generates format instructions from the record and parses the model's text back into the typed `Verdict`. Structured output is best-effort, not guaranteed — the model can emit an out-of-enum value or extra prose and the parse can throw — so I wrap it and fall back to `TRUST_CONSENSUS`. Confidence is an *enum*, not a double: a double is a fake number that implies a calibration the model doesn't have.
  - **Interview line:** *"BeanOutputConverter generates the format spec from my record and parses the response back into it — the boilerplate I hand-wrote in Phase 0. But structured output is best-effort, not a guarantee: the schema shapes the model, it doesn't constrain it like a compiler, so I wrap the parse and fall back. And I made confidence an enum, not a double, to keep the model classifying rather than faking precision."*

- **Feed the model the per-stat breakdown so it classifies disagreement *shape*, not magnitude.** Asking it to pick the bigger number is a coin flip in a lanyard. Given the breakdown it can reason: a touchdown-driven gap is fragile and regresses; a volume/role-driven gap is structural. And I stated the limit honestly — without news/depth-chart context it reasons about shape, not omniscience.

- **Never hold a DB transaction across an external (model) call.** A transaction pins a pooled connection for its whole lifetime; wrapping slow model calls in one is a long-running-transaction anti-pattern. I phased it: READ → REASON (model calls, no transaction) → WRITE (bounded txn) → RE-SCORE. The 12-minute run proved it: 12 minutes of model calls holding nothing, then a 4-second atomic write burst.
  - **Interview line:** *"The run spent twelve minutes reasoning and four seconds writing, and that ratio is the point of the phasing. All the slow, failure-prone model calls happened with no transaction open and nothing committed; the database work was a four-second atomic burst at the end. A batch that's 99% slow external calls must never hold a transaction across the slow part — and the phasing also made the whole run killable with zero partial state."*

- **The write and its derived recompute are one unit of work.** Reconciliation rewrites the mart, which leaves `player_scoring` stale for the touched players — so the endpoint chains a re-score of exactly that subset. One call leaves both tables consistent instead of a documented two-step trap.

- **One scoring contract over two storage types via covariant returns.** Widening `StatLine` to `Number` lets the integer-backed historical entity stay honest about being integer while projections are honestly `BigDecimal`; they unify at the scoring boundary. **Precision lives in the narrowing:** `new BigDecimal(n.toString())`, never `doubleValue()`, or the V7/V8 NUMERIC work leaks straight back out through a binary float.
  - **Interview line:** *"Two entities needed one scoring contract but stored numbers differently — historical stats are genuinely integer, projections are decimal. I widened the interface to their common supertype, Number, and narrowed to BigDecimal at the scoring boundary, so I never had to decimalize honest integer history just to fit the interface. And the narrowing has to go through the string form, or the NUMERIC columns I migrated to are undone by a float round-trip."*

- **The audit table is an anti-theater surface.** `player_projection_reconciliation` records cv / route / verdict / confidence / rationale / model — so I can answer "why is this projection what it is," and, crucially, *read the rationales to check the model reasoned over the breakdown rather than performing.*

- **The LLM call is 3–4 orders of magnitude slower than everything else, so latency moved.** My engine scores thousands of players in milliseconds; one model round-trip is seconds. The moment a model enters a loop, total time is purely a function of how many serial calls I make. The verdict calls are embarrassingly parallel, so the fix is bounded concurrency — but concurrency and rate-limit handling are one problem, not two (parallelize and you hit the provider's RPM ceiling and need 429 backoff).
  - **Interview line:** *"The first time I put a model call in a loop I learned latency had moved — my engine scores thousands of players in milliseconds, one model round-trip is seconds, so total time became purely how many serial calls I made. The calls are embarrassingly parallel, so the fix is bounded concurrency — but the bound isn't 'as many as possible,' it's 'as many as the rate limit allows,' so concurrency and 429 backoff are the same chapter."*

- **Route a change to the tool by blast radius, not line count.** A one-line fix that must stay consistent with conventions across files belongs in Claude Code (it reads them all); a self-contained pure function could be chat regardless of size.

- **An audit payload is scoped to the decision it supports, not a mirror of the wire.** ESPN's full record is ~5,000 lines I'd never replay; I store the *typed object I mapped from* (the scoped node) on both feeders. Lossless mirroring is right for a raw event log, wrong for a bounded landing row. And I keep the payload because the source is *mutable* — projections revise through the season — so the audit has to pin what the verdict was computed from at decision time, which re-fetching can't give me.
  - **Interview line:** *"The audit payload stores the typed object the mapper mapped from — the scoped projection node, not the raw response. The raw record is thousands of lines I'd never replay; the scoped node is exactly what the verdict was computed from, which is what an audit has to pin since the source revises through the season. 'I can always re-fetch' is false for a mutable source."*

- **ADP is observed market fact; the stat line is projected production — different authorities on the same row.** ADP must never follow the reconciliation verdict (favoring ESPN's *projection* says nothing about whose *draft-position sample* is better), and one format's ADP is never derivable from another's (Derrick Henry: 24.3 Standard, 34.5 PPR — the offset is player-specific). The mart's three ADP columns are copied verbatim from the source that reports all formats, regardless of which projection won.
  - **Interview line:** *"A row sourced part-ESPN, part-Sleeper isn't incoherent — it's attributing each field to its best source. The verdict judges projected production; ADP is an observed market fact about where people actually draft. One source being more accurate on touchdowns says nothing about whose draft-position sample is better, so ADP never follows the verdict, and per-format ADP is never derived from another format because the offset is player-specific."*

- **THE CHAPTER'S LESSON — the first verdict run flagged 94%, and the fix was retrieval, not a better prompt.** 120 of 128 came back `FLAG_UNCERTAIN`. My first read was "the model can't commit" and my first instinct was "make commitment the default" — **both wrong.** Reading the 7 commit-cases corrected me: the model committed confidently every time, but always by invoking the player's real depth-chart role — knowledge from its *training*, not my prompt. The 120 flags weren't risk-aversion; they were the model honestly hitting the boundary I'd written ("judge from the stat breakdown alone") on role disagreements that can't be settled from the disputed numbers. So I gave it the missing context — prior-season actuals, retrieved from my own `player_stats` (which *is* RAG, with a SQL query as the retriever) — and moved the honesty boundary to match: flag what's unadjudicable *even with history*. Re-run: 94% → 41% flags, bidirectional, rationales now citing the injected actuals. Rodgers flipped FLAG → FAVOR_HIGH with *"recent actuals (3322 in 2025, 3897 in 2024) strongly support the higher projection"* — the verdict provably changed *on the retrieved fact*. **This is the empirical proof of my Phase 0 note** (*"structure without grounding is just well-formatted hallucination"*): the 94%-flag run had perfect structured output — 128 clean parses, zero failures — and useless content. Grounding fixed it; structure never could.
  - **Interview line:** *"My first verdict run flagged 94% of contested players, and the fix wasn't a more aggressive prompt — it was retrieval. The model was abstaining honestly because I'd told it to judge from the projections alone, and a role disagreement can't be settled from the disputed numbers themselves. So I built the first RAG increment — retrieve the player's recent actual production from my own database, inject it as a baseline, and move the 'uncertain' boundary to mean 'unadjudicable even with history.' Abstention dropped from 94% to 41%, the recovered verdicts split in both directions — which is what tells me it's discriminating, not bullied — and one player flipped from uncertain to a confident call citing the specific actual that moved it. I expanded the model's information rather than pressuring its judgment."*

- **`FLAG_UNCERTAIN` is a feature, not a dodge — and a flag that *reasons through the history first* is the best outcome.** On Rodriguez (thin NFL history) the re-run stayed flagged, but now *because* the model pulled his actuals, found his 15-reception projection was "a role he has never held," resolved some dimensions and found the rest genuinely unsettled. Reasoning *to* uncertainty beats abstaining by default — and it caught a receiving-role detail I'd underweighted in my own pre-registration.
  - **Interview line:** *"The most valuable verdict isn't 'favor this source,' it's correctly recognizing the unpickable cases — and the best version reasons through the player's history first and then concludes uncertain, rather than abstaining by default. If I can't adjudicate it with more context than the model has, the model shouldn't pretend it can."*

- **Pre-registration is how you tell judgment from theater.** I predicted three players' verdicts from my own football reasoning *before* the model saw them, then checked whether the rationales cited the specific stat breakdown or generic boilerplate. The model matched all three and out-analyzed me on one — that's the blind test that says it's reasoning, not performing.

**Mistakes & lessons**
- **I misdiagnosed the 94% abstention and my first fix would have made it worse.** I read it as "can't commit → force commitment." The 7 commit-cases showed the opposite: it commits well when it has role knowledge and flags honestly when it doesn't. Lesson → **read the commit cases before diagnosing the abstentions.** Forcing commitment on a structurally-unadjudicable disagreement produces fabrication, not judgment.
- **`source_payload` shipped null under a green test suite** — the test asserted the columns I'd thought about and stayed silent on the one that was a stated requirement. A test only defends what it asserts.
- **Almost bundled the `SleeperClient` → RestClient migration into a null-column fix.** Caught it: a correctness fix and a standardization refactor justify themselves differently and belong in separate commits.
- **Talked the design down twice (RestClient, then lossless capture) and each time the smaller option was the more correct one.** The tell both times: the "more thorough" option was faithful to data the decision doesn't depend on.
- **Twelve-minute run scared me into reaching for the kill switch** — it wasn't a hang, just 128 serial model calls at ~5.6s each. Lesson → expect LLM-in-a-loop latency; the phasing meant a kill would have cost time, not data.

**To revisit**
- **Concurrency + rate limits:** bounded parallelism (~8 in flight) with 429 backoff to take the verdict run from ~12 min toward ~90 s. Concurrency and rate-limit handling are one design pass.
- **Per-format ADP into the mart (designed, not built):** drop raw `adp`/`adp_format`, add three `adp_*` columns sourced from Sleeper (all three present in the payload); the writer copies them verbatim from the rotowire raw row — never derived, never following the verdict.
- **JSONB `source_payload` round-trip test:** currently asserted at the mapper level only; the persistence path (through `@JdbcTypeCode(SqlTypes.JSON)` and the real column) is unasserted — add one `@DataJpaTest`.
- **`PlayerControllerTest` `@MockitoBean` one-liner** (pre-existing red, out of the reconciliation scope; land as its own commit).
- **RAG increment 2 if the re-run plateaus:** ADP and depth-chart/role context as additional retrievers behind the same seam; extract a `ContextRetriever` interface when retriever #2 lands (not before).
- **`SleeperClient` → RestClient** (still deferred; no longer entangled with anything).

**Confirmed this chapter (was open):**
- **The `BigDecimal` narrowing is honest** — confirmed by a player whose engine score *deliberately disagreed* with the source's own total: rotowire scores interceptions at −1, my league at −2, so Rodgers' total differs by design. That mismatch is the proof the engine re-scores from raw stats under my rules and isn't reading the source's pre-computed `appliedTotal` — i.e. the dispersion signal measures *production* disagreement, not *scoring-formula* disagreement.
  - **Interview line:** *"I confirmed my dispersion signal was honest by finding a player where my engine's total deliberately disagreed with the source's own — the source scores interceptions at −1, my league at −2. If the numbers had matched I'd have been measuring scoring-formula disagreement instead of production disagreement, the wrong thing entirely."*

---

### Phase 2 Reconciliation — Concepts Cheat-Sheet (additions)

**Spring AI / LLM integration**
- `BeanOutputConverter<T>` generates format instructions from a record and parses the response back into it — the framework version of hand-rolled "respond in JSON" + manual parse
- Structured output is best-effort, not enforced: wrap the parse, fall back on out-of-enum / malformed; the schema shapes the model, it doesn't constrain it like a compiler
- Model output should be an enum (classification), not a number — a `confidence` double is fake precision the model can't calibrate
- System prompt in config (`@ConfigurationProperties`), not a `static final` — prompt iteration becomes edit-and-restart, not recompile
- `ChatClient.Builder` → `defaultSystem(...)`; per-call model override via `AnthropicChatOptions.builder().model(...)`
- LLM latency is 3–4 orders of magnitude above DB/compute; once a call is in a loop, runtime = (serial call count × per-call latency); independent calls are embarrassingly parallel
- Bounded concurrency + 429 backoff are one design problem — the parallelism bound is the rate limit, not the core count

**RAG (retrieval-augmented generation)**
- RAG = retrieve relevant context from your store, inject into the prompt, let the model reason over it — a SQL query over your own tables is a legitimate retriever (vector search is one retriever, not the definition)
- A model invoking facts from its *training* (depth charts, roles) is unauditable and can be stale; *retrieving* the same facts makes the reasoning grounded and auditable
- Expand the model's information, don't pressure its judgment: if it abstains, ask what context it's missing before rewriting the prompt to be more aggressive
- Empty retrieval is signal, not omission ("no prior NFL production" *explains* why a rookie legitimately flags)
- Build the retrieval seam as a concrete class with one implementation; extract the interface when retriever #2 arrives (YAGNI on the abstraction, deliberate on the seam shape)
- Batch the retrieval — N independent model calls must not also become N per-row context queries

**Reconciliation / dispersion**
- Coefficient of variation (σ/μ) normalizes disagreement across magnitudes so one threshold means the same thing pool-wide; raw std dev secretly scales with player size
- Population std dev (all sources, not a sample); at n=2 CV = `|a−b|/(a+b)` and median = midpoint; robustness arrives at n=3
- Points floor before CV: guards μ→0 *and* bounds the contested subset (= bounds model cost)
- Calibrate the threshold against the real (often bimodal) distribution via a dry-run, not a round number; set it in the valley between agreement and violent disagreement
- The scoring format used for routing is a *detector*, not an output — score to measure spread, discard the number; output a format-invariant stat line
- PPR ⊇ Standard as a disagreement detector (PPR = Standard + receptions)
- The model classifies disagreement *shape* (TD-driven = fragile/regresses; volume/role = structural) from the per-stat breakdown — never picks "the bigger number"
- Verdict = selection rule over real stat lines (a source's line or a per-stat median); the engine writes the number, the model never emits one
- Pre-register expected verdicts from your own reasoning, then check rationales cite the breakdown vs. generic boilerplate — the blind test for judgment vs. theater

**Transactions & batch shape**
- Never hold a DB transaction across an external (model/HTTP) call — it pins a pooled connection for the call's whole duration (long-running-transaction anti-pattern)
- Phase it: READ → REASON (external calls, no txn) → WRITE (bounded txn) → derived recompute; the write phase running only after reasoning completes also makes the batch killable with zero partial state
- A write and its derived recompute (mart → scoring) are one unit of work — chain them so one call leaves both tables consistent
- Per-row resilience in a batch: one failed model call falls back to the safe verdict and is recorded; it never aborts the run

**Audit & provenance**
- An audit payload is scoped to the *decision* it supports, not a mirror of the wire — store the typed object you mapped from, not the 5,000-line raw record
- Keep the stored payload (don't "just re-fetch") when the source is *mutable* — the audit must pin what the decision was computed from at decision time
- A mart row can carry two provenances honestly: a reconciled estimate (stat line) and an observed fact (ADP) — different authorities, different sources, same row
- ADP never follows the reconciliation verdict; per-format ADP is never derived from another format (the offset is player-specific)

**Scoring contract / precision**
- Widen a shared interface to the common supertype (`Number`) so two entities with different honest storage types (`Integer` history, `BigDecimal` projections) satisfy one contract via covariant returns — don't decimalize honest integer data to fit the interface
- Precision lives in the narrowing: `new BigDecimal(n.toString())`, never `doubleValue()` — a float round-trip undoes NUMERIC storage
- Confirm the engine re-scores from raw stats (not the source's pre-computed total) by finding a player whose score *deliberately* differs from the source's own — a scoring-rule mismatch is the proof

**Workflow (additions)**
- Route a fix to chat vs. Claude Code by **blast radius** (how many files must stay consistent), not line count
- Keep a correctness fix and a standardization refactor in separate commits — different justifications, independent reverts
- The verdict-run iteration loop *is* the job: ship, measure the distribution, diagnose, fix the *information*, re-measure — the first run is rarely the right run
- Disable Claude Code's `Co-Authored-By` trailer via `attribution: { commit: "", pr: "" }` in `~/.claude/settings.json` (user-global) — authorship reflects the real division of labor (own the design/review, delegate the scaffolding)

---

## Phase 3 — Natural-Language League Customization ✅ COMPLETE

A user describes their league and draft style in plain English; an LLM parses it into a
validated spec; a deterministic resolver turns the spec into the engine's config; the
engine consumes it. The LLM translates intent to structure and **never originates a scoring
number** — the same spine as Phase 2, now pointed at *configuration* instead of *classification*.
Design owned in chat; multi-file build in Claude Code; three commits (engine refactor →
customization package → tests) plus a review-fix addendum.

### Phase 3 — What I built (concrete)

- **The `ScoringFormat` graduation (Commit 1, the prerequisite refactor).** The old enum
  bundled two independent axes (reception value + passing-TD points) into six combined
  constants and couldn't express position-dependent rules. Split it: `ReceptionFormat`
  (the reception axis alone), a `ScoringRules` value object (every multiplier a
  `BigDecimal`, the old `ScoringService` constants moved onto it), and `Position` threaded
  into `ScoringService.calculate(...)` so a TE reception bonus applies to TEs only. The six
  formats survive as **named presets** that resolve to `ScoringRules`; they reproduce the
  regression anchors (Barkley 208.50/226.00/243.50, Mahomes −2/INT) byte-identically.
- **The three-type safety boundary.** `ParsedLeague` (the LLM's narrow spec: preset + flags
  + extracted numbers) → `LeagueRulesResolver` (deterministic; owns the flag→number
  registry) → `LeagueRules` (the resolved domain object the engine consumes). The
  `TE_PREMIUM_BONUS = 0.5` lives only in the resolver — the model can set a `tePremium`
  flag but has no field to write `0.5` into.
- **The two-partition parse.** One `BeanOutputConverter<ParsedLeague>` call yields
  `LeagueRulesSpec` (engine-bound, hard-validated) *and* `DraftTactics` (strategy-bound,
  soft-validated, with a free-text tail). Different authorities, different destinations,
  different validation strictness.
- **Object-level validation + the validate-and-repair loop.** `LeagueRulesValidator` and
  `DraftTacticsValidator` (severity-classified `ValidationIssue`s: BLOCKING vs ASSUMPTION,
  collect-all, value-bearing messages), merged with the Jakarta annotation pass. `parse →
  refine → confirm` over a **stateless** service — the partial `ParsedLeague` rides in the
  payload, no `ChatMemory`. `RefineDriftGuard` diffs refine turns; a turn cap terminates the
  loop; `confirm` is the only writer (resolves, then persists, no LLM call in the txn) and
  refuses with 409 while anything BLOCKING remains.
- **Persistence.** `league_config` (V10): resolved scoring as typed `NUMERIC(4,2)` columns,
  roster columns, nullable playoff columns, `tactics` as JSONB via
  `@JdbcTypeCode(SqlTypes.JSON)`; `IDENTITY` id, insert-only (no `Persistable`).
- **The full test suite** (resolver/validator units, service orchestration, `@WebMvcTest`
  slice, JSONB tactics round-trip, and the resolve-before-persist captor test) plus a
  committed phase walkthrough at `docs/phase-3-overview.md`.

### Phase 3 — What I learned

- **Extraction vs. invention is a boundary you enforce with types, not prompts.** The model
  may *transcribe* a number the user stated; it may not *invent* a number the user only
  gestured at ("TE premium"). The enforcement isn't a well-behaved prompt — it's that the
  spec type has no field for the invented number. A flag, not a value.
  - **Interview line:** *"I draw the line at extraction versus invention. If the user states a number, the model transcribes it and validation clamps it; if the user states a label like 'TE premium,' the model emits a flag and a deterministic resolver maps the flag to a value from a registry. I enforce that with types, not prompt instructions — the model has no field to write the number into. A prompt is a request; a type is a guarantee."*

- **"User customization" is two parse problems with different authorities, not one.** League
  scoring is *objective config* (reported facts → the engine, hard-validated, must be exactly
  right). Draft tactics are *subjective preferences* (→ the strategy LLM, soft-validated, no
  correct answer). Conflating them lets a vague tactic silently rewrite how points are computed.
  - **Interview line:** *"Natural-language customization looks like one extraction task but it's two with different authorities. Scoring rules are objective facts the engine consumes and must nail; draft tactics are preferences that steer a model and have no correct answer. I parse them into separate structures with separate validation, so a vague tactic can never rewrite the scoring."*

- **How rigid a parse target must be is dictated by its consumer, and failure-cost drives
  validation-strictness.** The rules spec has *no* free-text escape hatch because a
  deterministic engine consumes it (prose is useless to it) and a misparse corrupts every
  number → hard reject-and-repair. Tactics get a `freeformNotes` tail because an LLM consumes
  them and a misread just yields slightly worse advice → soft, degrade-gracefully.
  - **Interview line:** *"The rigidity of a parsed object is set by its consumer. Rules feed a deterministic engine, so the object is fully typed with no catch-all and rejects anything it can't resolve; tactics feed a language model, so they carry a free-text tail and degrade gracefully. Same input paragraph, opposite strictness, because the failure costs are opposite."*

- **Scoring is a closed world; tactics are an open world — model each as what it is.** You can
  enumerate every scoring dimension (finite vocabulary → exhaustively typed). You cannot
  enumerate every way a person thinks about drafting (user-invented → a typed core plus a
  free-text tail). A tactic *graduates* to a typed field only when a consumer that can
  mechanically act on it exists — three tiers: closed-enum leans, parameterized constraints,
  and the open prose tail. Stacking stays prose until Phase 4 has the teams data and
  correlation logic to act on it.
  - **Interview line:** *"I type the closed world and leave a structured escape hatch for the open one. Scoring has a finite vocabulary, so it's fully typed; tactics are open-ended and user-invented, so there's no complete set to enumerate. A tactic earns a typed field only when a consumer can mechanically act on it — otherwise it's a slot nothing reads. Stacking stays free text until the draft agent and team data exist."*

- **Config has no safe silent fallback — so the loop is validate-and-repair, not default.**
  Phase 2 could fall back to `TRUST_CONSENSUS` on a bad parse. Config can't: defaulting a
  misparsed league to "standard PPR" silently misconfigures every downstream point. So a
  failed parse surfaces as a BLOCKING issue, the user repairs, and an explicit `confirm` gate
  is the only writer.
  - **Interview line:** *"The right fallback depends on whether a silent default is safe. Reconciliation could default to the consensus median; league config can't, because a wrong default silently corrupts every projected number. So config parsing has no silent fallback — it surfaces the gap, asks, and only writes after an explicit confirm."*

- **A config-gathering parse has three failure kinds, and they map to three loop behaviors.**
  Structurally invalid → reject and re-ask. Missing-with-no-safe-default → block and ask.
  Complete-but-assumption-bearing → proceed but surface the assumption. The whole product
  quality is telling case 2 from case 3 — which user silences you can fill quietly, and which
  you must ask about (the one field that moves every number is never filled quietly).
  - **Interview line:** *"Validating gathered config isn't one check, it's three: invalid input gets rejected, a missing field with no safe default blocks, and a missing field with a safe default proceeds but surfaces the assumption. The quality is entirely in telling the second case from the third."*

- **The validation layer should mirror the parse-target authority split — and severity-by-prefix
  only reclassifies violations that actually fire.** A rules validator (hard) and a tactics
  validator (soft) mirror the two partitions. But a "demote everything under `tactics.*` to
  ASSUMPTION" rule silently passes a field that has *no constraint* — there's no violation to
  demote. The one tactics field with a mechanical consumer (`earliestRoundByPosition`) needed
  an explicit BLOCKING bound in the object validator, because it's the exception the prefix
  rule can't see.
  - **Interview line:** *"Severity-by-path-prefix is elegant but it only reclassifies violations that fire — a field with no constraint produces nothing to reclassify and passes silently. The one tactics field a deterministic consumer acts on gets an explicit blocking check in its own validator, and I mirror the parse-target authority split in the validation layer."*

- **Cross-field rules are a separate programmatic layer, and the validator's message is the
  content of the next question.** `playoffTeams ≤ teamCount` can't be an annotation. And
  because the messages feed the repair prompt, I went programmatic and value-bearing
  ("Playoff teams (8) cannot exceed league size (6)") and collect-all, not fail-first — the
  model fixes everything in one refine turn only if it sees everything at once.
  - **Interview line:** *"Field annotations validate within a field; relationships between fields need object-level validation. I made it programmatic rather than a class-level annotation because the validator's output is the content of the next question to the user — it has to name the offending values and collect every issue in one pass, which templated constraint messages can't do."*

- **Multi-turn does not mean conversation memory — carry the typed object, not the transcript.**
  The reflex is `ChatMemory` replaying prior turns. But the state here isn't a dialogue, it's a
  partially-filled typed object; the conversation just edits it. Stateless server, object in the
  payload → smaller context, typed (not prose) state, every turn independently testable, no
  session store. `ChatMemory` is reserved for Phase 4's genuinely conversational agent.
  - **Interview line:** *"The reflex for multi-turn is conversation memory, but for config gathering the state isn't the dialogue — it's a partial typed object the conversation edits. I kept the server stateless and carried the object in the payload: smaller context, typed state instead of prose, every turn testable, no session store. Match the state mechanism to what the state actually is."*

- **A model handed a correction rewrites fields you didn't ask about — carrying the prior object
  is what makes drift *detectable*.** `RefineDriftGuard` diffs before/after deterministically and
  surfaces every change as an ASSUMPTION (never BLOCKING, so drift can't dead-lock the loop). It
  refuses the semantic judgment of "was this asked for" — the intended change reads as
  confirmation, anything else as drift for the user to reject.
  - **Interview line:** *"When you re-parse a correction against existing state, the model 'helpfully' rewrites untouched fields. Because I carry the prior typed object, I can diff old against new and surface every change for confirmation. I don't ask the model to judge what the correction 'addressed' — that's the semantic call I refuse to fake; deterministic over clever."*

- **`BigDecimal.equals` is scale-sensitive; `compareTo` is not — and this one fact surfaced in
  four disguises.** `new BigDecimal("4").equals("4.0")` is false; `compareTo` is 0. Persistence
  *normalizes scale* (a `NUMERIC(4,2)` round-trip returns `4.00`), so any equals-based comparison
  of a persisted decimal is a latent bug. It bit the **drift guard** (a `4`→`4.0` refine read as
  drift), the **persistence round-trip test** (asserted by `isEqualByComparingTo`, not `equals`),
  **record equality** (`ParsedLeague` inherits scale-sensitive `equals` from its `BigDecimal`
  field), and **Mockito argument matching** (`eq(current)` after a JSON round-trip). The
  recurrence is what tells me it's fundamental, not incidental.
  - **Interview line:** *"`BigDecimal.equals` compares scale and `compareTo` compares value — `4` and `4.0` are unequal by equals, equal by compareTo. It matters most around persistence, because reading a value back from a NUMERIC column normalizes its scale, so any equals-based comparison of a round-tripped BigDecimal is a latent bug. I compare decimals by value and assert by compareTo — in comparators, record equality, and argument matchers alike."*

- **`Persistable` is machinery that defends against a problem the ID strategy already prevents.**
  The upsert pattern exists to defeat Spring Data's exists-check on *assigned* IDs. With
  `IDENTITY` generation the ID is null pre-insert, so Spring Data already treats the entity as
  new — `Persistable` earns nothing on an insert-only table, and its `@JsonIgnore` on `getId()`
  was even hiding the generated id from the confirm response. Cargo-culted convention; stripped it.
  - **Interview line:** *"`Persistable` overrides Spring Data's exists-check on assigned IDs. With IDENTITY generation the ID is null before insert, so the framework already knows the entity is new — adding `Persistable` there defends against a problem the ID strategy already prevents. I strip patterns that don't earn their keep in the specific case."*

- **A boundary test asserts a value that can't exist on the near side of the boundary — and you
  verify your own test's precondition first.** Unit-testing the resolver and the persistence
  separately doesn't test the *seam* (`confirm` persists resolved values, not the raw spec). My
  first attempt captured the saved entity but asserted `HALF_PPR` and `-2` — values the spec
  already carried, so it couldn't tell resolved from raw. The fix: a fixture where resolution
  *transforms* the input (`tePremium=true` → `teReceptionBonus=0.5`, a number that exists nowhere
  in the parse targets). And before writing the captor, confirm the path *reaches* `save` under
  that fixture (a null passing-TD is ASSUMPTION, not BLOCKING) — else it trips the gate and
  captures nothing.
  - **Interview line:** *"A boundary test has to assert a value that can only exist on the far side of the boundary. My first attempt asserted values the spec already contained, so it passed whether or not resolution ran; the fix was a fixture where a TE-premium flag becomes a 0.5 bonus that exists nowhere in the parse targets. And I verify the fixture actually reaches the mocked call — a test that trips an earlier guard captures nothing and fails for the wrong reason."*

- **Derived tables rebuild from source; migrations are immutable history.** I deleted
  `league_config`'s predecessor thinking (`player_scoring`) mid-phase — recovery was a one-command
  recompute, because a derived table's version history *is* recomputation. The incident validated
  the "don't version derived tables" design rather than exposing a gap. You version source-of-truth
  and mutable inputs (where the history is a feature, e.g. the reconciliation audit), not deterministic outputs.
  - **Interview line:** *"I version source-of-truth and mutable inputs where prior state can't be regenerated; I don't version derived tables, because recomputation from source is their history. It's the warehouse rule: version the dimension, rebuild the fact."*

- **Review an AI agent's diff, not its self-report.** A self-report is self-consistent by
  construction — the agent that wrote the code wrote the summary. Across this phase I verified the
  agent's work against something *independent* every time: the pre-recorded regression anchors for
  the scoring refactor, the actual patch (not the narrative) for the addendum, the captured
  argument for the boundary. The agent's own self-review even caught a `BigDecimal` drift-guard
  consequence I'd missed — good, but I still read the diff.
  - **Interview line:** *"I review an AI coding agent by reading the diff, not its summary — the summary is self-consistent by construction. I verify against something the agent's reasoning can't contaminate: a regression number recorded before the change, the actual patch, a captured argument. Trust the tool, verify the artifact."*

**Mistakes & lessons**
- **Special-cased `stackQbWithReceiver` as a typed boolean one message after arguing *for* the
  free-text escape hatch.** Caught in review — an open-set tactic in a structured slot is exactly
  what the tail exists to prevent. Lesson: the graduation rule (typed field only when a consumer
  can act on it) applies to *me* too, mid-design.
- **First boundary test asserted values the spec already contained** (`HALF_PPR`, `-2`) → couldn't
  distinguish resolved from raw. Fixed with a transforming fixture (`0.5`). Testing each half of a
  seam in isolation is not testing the seam.
- **The review-fix addendum I wrote had a severity-misclassification hole I only half-saw**, and it
  was the *agent's* self-review that surfaced the `BigDecimal` drift consequence of my own Fix A.
  Lesson: blast-radius reasoning is the reviewer's core job, and I'd outsourced part of it — read
  the second-order effects of a type change before signing off.
- **`NUMERIC(4,1)` vs `(4,2)` inconsistency across three "points" columns** — integer inputs in
  decimal columns of *differing* scale, the inconsistent middle. Standardized on `(4,2)` and moved
  the spec fields to `BigDecimal` so fractional formats are expressible.
- **Reached for "should we version the tables" after the delete** — wrong reflex. The derived
  table's recovery *is* recomputation; adding temporal columns there stores redundant state that
  can drift from its source.

**To revisit**
- **Assumed-default asymmetry (surface both or neither):** the validator surfaces a
  `passingTdPoints`-null ASSUMPTION but stays silent on a null `interceptionPoints` — and, as the
  live runs showed, silent on *all* the prompt-supplied roster defaults (an unstated roster comes
  back as a full 1QB/2RB/2WR/1TE/1FLEX/6-bench with zero issues, while unstated scoring is
  flagged). Roster defaults are lower-stakes (they shift VORP, not points), so silent-default may
  be the right call — but right now it's an accident of which fields got ASSUMPTION coverage, not
  a decision. Decide the policy once, apply it to scoring *and* roster, its own commit (the
  current boundary test quietly depends on the current behavior).
- **`earliestRoundByPosition ≤ total draft length`** — a stronger cross-field check, deliberately
  *not* taken to avoid coupling the tactics validator to the engine-bound `RosterSpec` across the
  authority split. Reconsider if a real user hits it.
- **Phase 4 custom-league scoring — the preset short-circuit** (from `docs/phase-3-overview.md`
  §5.1): compare a confirmed config's resolved `ScoringRules` to the six `ScoringFormat` presets
  by `compareTo`; read precomputed `player_scoring` on a hit, re-score in-memory on a miss; never
  write custom scores into `player_scoring` (its key is the closed format enum).
- **Teams entity + NFL schedule ETL + depth-chart/role retriever (RAG increment 2)** — the deferred
  "current-context ingestion" Phase 4 stacking and playoff strength-of-schedule need. The
  depth-chart retriever is also the correctness fix for the traded-backup case reconciliation
  can't currently adjudicate (prior actuals encode the *old* role).
- **`ChatMemory`** for the genuinely conversational Phase 4 draft agent (deliberately unused here).
- **Closed this phase (was a carried-forward Phase 2 item):** the JSONB persistence-path round-trip
  test — `tacticsRoundTripsThroughJsonb` exercises the same `@JdbcTypeCode(SqlTypes.JSON)` path,
  asserted by record equality after flush/clear.

---

### Phase 3 — Concepts Cheat-Sheet (additions)

**Structured output for config (vs. classification)**
- The LLM's output type is **not** the engine's input type: a narrow spec → a deterministic resolver → the domain object; the flag→number registry lives in the resolver, so the model has no field to emit a number into
- Extraction vs. invention: transcribing a stated number is fine (clamp it); inventing an unstated one is not — that's a flag the engine resolves
- Preset-plus-deltas keeps the LLM output narrow and auditable: a closed-enum base preset + a few flags + a few extracted numbers; never build the full rules object field-by-field
- Two partitions from one `BeanOutputConverter` call, split by authority: engine-bound rules (hard) vs. strategy-bound tactics (soft, free-text tail)
- Rigidity of a parse target = rigidity of its consumer; failure-cost asymmetry → validation-strictness asymmetry
- Closed world (scoring) → exhaustively typed, no catch-all; open world (tactics) → typed core + free-text tail; graduate a field only when a mechanical consumer exists
- No safe silent fallback for config: surface the gap, don't default (contrast Phase 2's `TRUST_CONSENSUS`)

**Validation (object-level, severity, repair loop)**
- Three failure kinds → three behaviors: invalid (reject), missing-no-default (block+ask), missing-with-default (proceed+surface assumption)
- Cross-field rules can't be annotations; go programmatic + value-bearing + collect-all because the message *is* the next repair question
- The validation layer mirrors the parse-target authority split (rules validator hard, tactics validator soft)
- Severity-by-prefix only reclassifies violations that fire — an unconstrained field has nothing to demote and passes silently; give the one Tier-2 tactic an explicit bound
- Bean Validation cascades into nested records only with `@Valid`; `@DecimalMin`/`@DecimalMax` (not `@Min`/`@Max`) for `BigDecimal`; both treat `null` as valid, so nullable-with-bounds works

**Multi-turn & state**
- Multi-turn ≠ `ChatMemory`: if the state is a partial typed object, carry it in the payload and keep the server stateless — smaller context, typed state, testable per turn
- The loop must terminate: a turn cap that returns the partial object + unresolved issues, no model call past the cap
- A failed repair turn must keep the prior object — never lose accumulated state on a model error
- Refine drift is real: carry the prior object, diff deterministically, surface every change as a non-blocking ASSUMPTION; refuse the semantic "was it asked for" judgment
- `confirm` re-validates from scratch (a prior READY proves nothing about the payload just handed over) and is the only writer

**BigDecimal & persistence**
- `equals` is scale-sensitive, `compareTo` is not; persistence normalizes scale, so equals-based comparison of a round-tripped decimal is a latent bug — it recurs in comparators, record equality, and Mockito matchers
- Standardize `NUMERIC(p,s)` scale across columns that share a unit; move spec fields to `BigDecimal` so fractional formats are expressible
- `Persistable` defends against the exists-check on *assigned* IDs; with `IDENTITY` it earns nothing (and its `@JsonIgnore getId()` can hide the generated id from the response) — strip patterns that don't pay in the specific case
- JSONB via `@JdbcTypeCode(SqlTypes.JSON)` on the typed object; assert the round-trip by record equality after `flush()`+`clear()` (the `clear()` forces the read from the column, not the persistence cache)
- Migrations are immutable history; a brand-new, unapplied migration can be edited in place, an applied one needs a follow-up `ALTER`; derived/empty tables just rebuild

**Testing & AI-agent workflow**
- A boundary test asserts a value that can only exist on the far side of the boundary (capture the entity handed to `save`; assert the resolved `0.5`, not a value the spec already carried)
- Verify a test's own precondition — that the path reaches the mocked call under your fixture — before trusting a captor/`verify`
- Review an agent's **diff**, not its self-report: the report is self-consistent by construction; verify against an independent artifact (a pre-recorded anchor, the actual patch, a captured argument)
- Commit a phase-scoped architecture walkthrough (`docs/phase-N-overview.md`) separate from the README and freeze it per phase; a "drift from spec" section is where the engineering judgment shows
- Route by blast radius; keep a correctness fix and a standardization refactor (and a fix and its defending test) in the same-or-separate commits deliberately, not by accident

---

## Phase 4 — AI Draft Assistant Agent (IN PROGRESS)

The phase centerpiece is the shift from **pre-injection RAG** (Phases 2–3: Java decides what
the model needs, fetches it, injects it into one call) to **agentic tool calling** (the model
decides mid-reasoning what it needs and requests it by emitting a structured call against a
schema my Java publishes — my code executes, the model never runs anything). The extraction-
vs-invention boundary survives the inversion: the model went from *receiving* facts to
*requesting* them, and it still never originates a number. Increment map: **4.1** draft domain
+ deterministic value engine (no LLM) → **4.2** the agent loop (tools, `ChatMemory`) →
**4.3** current-context ingestion (teams/schedule/depth charts) → **4.4** vector news RAG →
**4.5** strategy layer (stacking graduation). Ordering rationale: apply the graduation rule to
*data* — build a retrieval increment only when a consumer has demonstrated the need — and
deliberately re-run the Phase 2 arc: let the starved 4.2 agent fail visibly, and let the
failure specify what 4.3/4.4 must retrieve.

### Phase 4.1 — Draft Domain + Deterministic Value Engine ✅ COMPLETE

The substrate the agent's most load-bearing tools will wrap: persisted draft state and a
VORP-ranked board computed from the confirmed league's roster shape. Zero LLM code, by
design. Three commits (ADP promotion → draft domain → valuation engine) after a four-finding
review round.

### Phase 4.1 — What I built (concrete)

- **Per-format ADP promotion (Task 0 / Commit 1).** V11 swaps the raw landing table's single
  `adp`/`adp_format` for `adp_std`/`adp_half_ppr`/`adp_ppr` (Sleeper publishes all three;
  ESPN's lone PPR ADP dropped deliberately) and widens the mart's V5 columns to `NUMERIC(6,2)`.
  `ReconciliationWriter` copies the three values **verbatim from the rotowire raw row** on
  every mart write, regardless of route or verdict — ADP is observed market fact, never
  derived, never verdict-following. Backfilled 2026: 241 draftable players carry ADP.
- **Draft domain (V12 + `draft/` package, Commit 2).** `draft_session` (IDENTITY, frozen
  `team_count`/`total_rounds` snapshots, status the only mutable field) + insert-only
  `draft_pick` (composite PK `(session_id, overall_pick_no)`, `Persistable`,
  `UNIQUE (session_id, player_id)`). `SnakeOrder` — pure 1-based snake arithmetic
  (`teamFor`, `overallPickFor`, `nextPickFor`, `picksUntilNextTurn`), exhaustively tested
  incl. the slot-8/T=10 gap fixture (after pick 8, next is 13; picks 9–12 belong to teams
  9,10,10,9 — two opponents picking twice each). `DraftService`: server-assigned pick numbers
  (the request carries only `playerId`), COMPLETE flip on the final pick via dirty checking,
  constraint-violation-on-duplicate translated to the same 409 as the pre-check.
  `DraftStateView`: user roster, `unfilledSlots` (greedy dedicated → FLEX → SUPERFLEX →
  BENCH), and `gapTeams` — per-opponent positional counts for the slots picking before my
  next turn (the survivability substrate; counts only, no player dumps — tool-result budget
  discipline starts one increment before the tools exist).
- **Valuation engine (`valuation/` package, Commit 3).** `LeagueConfig.toScoringRules()` /
  `toLeagueSettings()` (pure mapping, throws on impossible nulls — never defaults);
  `AdpBucket.forReceptionPoints` (nearest-bucket for custom rules, with an `@EnumSource`
  invariant test proving it reproduces `ScoringFormat.adpBucket()` on all six presets);
  `ReplacementLevelCalculator` (pure greedy absorption: dedicated starters → flex over the
  eligible set → superflex with QB added; starters-only baseline, bench is option value left
  to the LLM); `DraftBoardService` scoring the mart **in memory** under the league's resolved
  rules on one uniform path (no preset short-circuit — ~400 `calculate` calls are trivial and
  precomputation hasn't earned a read path), static pre-draft baseline (the board drains;
  season-long scarcity doesn't), VORP-ranked rows with the format-matched ADP column.
- **Mart hygiene.** Purged ~2800 pre-reconciliation rows the board would have ranked
  alongside the 409 contract-correct ones — surfaced by two numbers in one report that
  couldn't both be true (mart "3217 rows" vs. reconcile "wrote 409").

### Phase 4.1 — What I learned

- **VORP: value is scarcity-adjusted, not absolute.** `VORP = projected points − replacement
  level(position)`, where replacement level is the best player *outside* the league-wide
  startable pool — and the startable pool is a pure function of the confirmed roster shape
  (dedicated starters + greedy flex/superflex absorption). A 340-point QB can be worth less
  than a 260-point RB because the 11th QB is nearly free while the 21st RB is not. This is
  where the Phase 3 config finally does load-bearing work.
  - **Interview line:** *"Raw projections rank production; VORP ranks scarcity-adjusted value —
    points above the best player outside the startable pool, derived deterministically from
    the league's roster shape including greedy flex allocation. The arithmetic lives in the
    Java engine; the LLM will reason about tier cliffs over those numbers but never computes one."*

- **Snapshot vs. input — a value living in two places will eventually disagree.** The review's
  one real design flaw: `StartDraftRequest` carried `teamCount` while the config row also
  carried it, and nothing reconciled them — `ReplacementLevelCalculator` even received the
  fact twice (session's count + `LeagueSettings.teams()`) with one copy dead. Every test
  passed because every fixture happened to agree. Fix: the client names the config and its
  slot; `teamCount` and `totalRounds` are snapshots frozen from the config at session
  creation (like an FX rate frozen on a booked trade — the derivation's *input* can change
  mid-draft, so the derived value is captured as a fact).
  - **Interview line:** *"I distinguish snapshot from input: the draft session freezes
    teamCount and totalRounds from the league config at creation — the client can't supply a
    value the config already owns, so the draft can never disagree with its league. The tell
    was a calculator receiving the same fact through two parameters and ignoring one; my
    tests missed it because every fixture happened to agree."*

- **The server owns the sequence; the client reports the fact.** `overall_pick_no` is
  assigned server-side (max + 1, inside the transaction) — a client-supplied sequence number
  invites desync (a missed entry renumbers everything after it) and races. The composite PK
  is the concurrency backstop: simultaneous inserts computing the same next-number collide
  and one fails cleanly — the ledger assigns its own transaction numbers.
  - **Interview line:** *"The client reports the observed fact — who was picked; the server
    owns the sequence inside the transaction, with the composite primary key as the
    concurrency backstop. Same reason a ledger assigns its own transaction numbers."*

- **Store the observed fact, derive the rest — and document the absence.** All 130 picks are
  persisted (opponent rosters matter: "will he make it back to me" is *the* live-draft
  question), but there is no `team_no` column — snake assignment is arithmetic over
  `(overall_pick_no, team_count)`, and persisting a derivation invites drift from its source.
  The javadoc states *why the field is absent*, which is the guard against a future
  contributor helpfully adding it back.
  - **Interview line:** *"I derive snake-draft team assignment from the pick number instead of
    storing it — persisting a derivation invites drift. And I document the absence: a missing
    column looks like an oversight unless the design says it's a decision."*

- **Defense in depth, with the match done properly.** Can't-draft-twice is enforced twice
  deliberately: a service pre-check for the friendly 409 (carrying the pick that took the
  player), and `uq_draft_pick_player` as the final arbiter under races or a future second
  writer. The violation match walks the cause chain to Hibernate's
  `ConstraintViolationException` and compares `getConstraintName()` (message-grep is
  dialect-fragile) — and the integration test proves the *precondition*: that the real
  Postgres dialect actually populates the name the match relies on.
  - **Interview line:** *"The application check handles the happy path and the friendly error;
    the unique constraint guarantees the invariant under concurrency or a writer that doesn't
    exist yet. I match violations by Hibernate's extracted constraint name, and I
    integration-test that the dialect populates it — the assumption my match rests on."*

- **Design failure modes to degrade toward noisy, not toward convincing.** Twice this
  increment, unprompted the second time: if constraint-name extraction ever fails, a genuine
  duplicate becomes a loud 500 rather than a plausible wrong 409; and a degenerate config
  (`teamCount=1`) now fails on the V12 CHECK as a 500 rather than a polite request-validation
  400 — corrupt reference data isn't the client's fault, and a 400 would misattribute it.
  - **Interview line:** *"I design failure modes to degrade toward noisy: a failed constraint-
    name match produces a loud 500, never a plausible wrong 409. A failed batch job is
    recoverable; a batch job that posts wrong ledger entries is the one that survives to
    production."*

- **JPA dirty checking — mutation is the write instruction (interview detour).** The COMPLETE
  flip has no `save()` call: the session was loaded through the repository inside the
  `@Transactional` method, so it's *managed* — Hibernate snapshots it at load and flushes an
  UPDATE at commit for anything that changed. The unit-of-work pattern: the transaction
  tracks its own dirty pages. Traps on both sides: mutate a *detached* entity (transaction
  already closed) and the change silently vanishes; mutate a managed entity you only meant to
  read and Hibernate issues an UPDATE you never asked for.
  - **Interview line:** *"Inside a transaction, loaded entities are managed — Hibernate
    snapshots at load and flushes an UPDATE at commit for whatever changed, so save() on a
    mutation is redundant. The trap is the detached side: mutate after the transaction closed
    and nothing is written; the flip side is accidental UPDATEs from mutating an entity you
    only meant to read."*

- **Validation order follows data dependency.** Removing `teamCount` from the request moved
  the config load ahead of the slot check (the slot's upper bound *is* a property of the
  config), which changed an observable behavior: unknown-config + bad-slot now 404s where it
  used to 400. The reordering is the semantically honest one; the test that previously
  asserted "repo untouched" inverted into one documenting the dependency.

- **Purge economics: rebuild a derived table when inputs or logic changed, not to reassure
  yourself.** The stale mart rows were purged without re-reconciling — the 409 survivors
  *were* yesterday's run over the current raw data, so a re-run would re-spend ~128 model
  calls to reproduce the same lines modulo verdict noise. And the purge didn't shrink the
  data so much as make the contract true: "reconciliation is the only writer" went from
  documentation to an observable property of the table.
  - **Interview line:** *"I purged stale pre-contract rows without recomputing — the survivors
    were already the current pipeline's output over the current inputs. Rebuild a derived
    table when its inputs or logic changed, not to reassure yourself."*

- **Size the load before designing around it.** The persistence-cadence worry (a pick every
  60–90s) dissolved on arithmetic: ~0.02 inserts/second against a database comfortable at
  tens of thousands — nine orders of magnitude of headroom. Phase 4's real latency pressure
  is the *agent's sequential tool-call loop* (multiple model round-trips per advice request
  while I'm on the clock), and that's where the design budget goes in 4.2.

- **Diff-vs-report, twice more.** (1) The agent claimed the mart had ADP columns "since V5,
  never populated" — the *deleted lines* proved it: the removed carry-forward code called
  `existing.getAdpStd()`, which can't compile against an entity lacking the field. Removed
  code corroborates independently of the narrative. (2) The mart-count discrepancy (3217 vs.
  409 written) was caught because two numbers in the same report couldn't both be true — the
  number that doesn't reconcile with its neighbor *is* the finding.

### Phase 4.1 — Mistakes & lessons

- **I put `teamCount` in the request DTO** — created the dual-source flaw the review round
  had to catch. The design conversation had even settled the principle for `totalRounds`;
  I failed to apply it to the fact sitting next to it. Lesson: when you freeze one config
  fact into a session, audit every *other* request field against the same rule.
- **Finding 1 initially went unreported in the fix round.** Findings 2–4 came back with
  evidence; the one marked "fix before commit 2" — the only one changing runtime numbers —
  was silent, and surfaced only when the reviewer asked. Lesson: a review loop where three of
  four items return with evidence trains everyone to assume the fourth did too; track
  findings as an explicit checklist and report each closed-or-open, never narratively.
- **The spec assumed ~310 mart rows** — that was the dual-source subset, not the mart. The
  wrong assumption was harmless here but it's the same class of error as the teamCount flaw:
  a number carried from memory instead of read from the system.

### Phase 4.1 — To revisit

- **Concurrent-pick PK collision** (`TODO(4.x)` on the catch block): two simultaneous
  `recordPick` calls collide on the composite PK — correctly *not* matched as a duplicate-
  player 409, so one caller 500s. Acceptable single-writer; translate to retry-once or a 409
  "pick number contended" when the Sleeper live-draft sync becomes a second writer.
- **Leaderboard row-count change (documented, deliberate):** projection-scored players for
  2026 dropped ~3200 → 409 with the mart purge. Not a regression — the contract became true.
  Recorded here so future-me doesn't rediscover it as a "data loss bug."
- **Preset short-circuit stays deferred** (decision recorded against phase-3-overview §5.1):
  in-memory scoring is one uniform path for preset and custom leagues; precomputation earns
  its complexity only when a read path exists where it pays.
- **Four ranked-but-sub-floor ADP rows** (K/DST-shaped) left with the purge — in scope only
  if K/DST ever enter the board.
- **Next: 4.2 — the agent loop.** Raw tool-calling exercise first (own the loop without
  Spring AI in the room), then `@Tool` + `ChatClient` over the 4.1 substrate, `ChatMemory`
  off the bench, tool contracts as compact DTOs (the `DraftStateView`/`DraftBoardView` shapes
  were the dry run), loop caps and token-growth discipline.

### Phase 4.1 — Concepts Cheat-Sheet (additions)

**Tool calling (the Phase 4 shift)**
- Pre-injection RAG: Java decides what the model needs *before* the call; agentic tool
  calling: the model requests it *mid-reasoning* via a structured call against a published
  schema — Java executes, the model never runs code and still never originates a number
- The tradeoff: latency and loop control bought context efficiency — a draft turn can't
  pre-load 400 players, and the answer's context isn't knowable until reasoning starts
- Tool results re-enter the context, so tools return compact DTOs (counts and numbers, no
  entity dumps) — budget discipline starts in the DTO design, one increment before the tools

**Draft/valuation math**
- VORP = points − replacement level; replacement level = best player outside the startable
  pool; startable pool = dedicated starters + greedy flex/superflex absorption over the
  flex-eligible set; starters-only baseline (bench is option value = LLM judgment)
- Static pre-draft baseline: the board drains during a draft, season-long scarcity doesn't
- Snake arithmetic: round `r = ceil(p/T)`; odd rounds forward, even reversed; team-for-pick
  is derived, never stored; the turn slots pick back-to-back (survivability is concentrated)
- ADP is observed market fact: copied verbatim per format, never derived across formats,
  never verdict-following; custom rules map to the nearest published bucket by `compareTo`

**Persistence & transactions**
- Snapshot vs. input: freeze config-owned facts into the session at creation; never accept
  them from the client; a value in two places will eventually disagree
- Server-assigned sequencing inside the transaction; composite PK as the concurrency backstop
- Dirty checking: managed entities flush their mutations at commit — no save() needed; the
  detached-entity trap on one side, accidental UPDATEs on the other
- Constraint matching: walk the cause chain to Hibernate's `ConstraintViolationException`,
  compare `getConstraintName()`, and integration-test that the dialect populates it
- Failure modes degrade toward noisy (loud 500) rather than convincing (plausible wrong 409)

**Process**
- Track review findings as an explicit checklist; report each closed-or-open, never narratively
- Deleted code corroborates a claim independently of the narrative; two numbers in one report
  that can't both be true are the finding
- Rebuild derived data when inputs or logic changed, not for reassurance

### Phase 4.2 — The Agent Loop, Part 1: Raw Tool-Calling Exercise ✅ COMPLETE

Phase 0 discipline applied to agents: own the tool-calling loop by hand before letting Spring
AI own it. Two files in `claude-playground`, tools implemented as HTTP calls against my
*running* read-option instance — real data, zero product code touched, the whole `stop_reason:
tool_use → tool_result → repeat` cycle experienced with my own eyes. The point was never to
ship this; it was to make "an agent" concrete so the framework never becomes magic.

### Phase 4.2 (raw loop) — What I built (concrete)

- **`ReadOptionApiClient`** — a thin `java.net.http` client wrapping three read-option
  endpoints (state, board, profile) as callable tools, returning a `ToolResult(String content,
  boolean isError)` record. Two scope facts are **constructor-bound, never tool parameters**:
  `sessionId` and `scoringFormat`. Non-200 and unreachable-host both map to
  `ToolResult(..., isError=true)` — the failure becomes data for the model, not an exception
  for Java.
- **`AgentLoop`** — the hand-written loop: a `TOOLS_JSON` schema string (hand-authored JSON
  Schema, the thing Spring AI will later generate from method signatures), `callClaude` posting
  the accumulated transcript, a `stop_reason` switch (`end_turn` → done, `tool_use` → dispatch,
  anything else → throw), an inner `for` over the response's content blocks that collects **all**
  `tool_use` blocks and returns **all** their results in **one** batched `user` message, an
  `is_error: true` field on failed results, a `MAX_ITERATIONS` cap that throws loud, and
  per-iteration logging of stop reason, input/output tokens, cumulative input tokens, and
  latency.
- **Test method: three prompts, ascending complexity.** (1) "Who should I pick now?" — expect
  state+board in parallel. (2) "TE now or wait?" — expect gapTeams survivability reasoning.
  (3) "Compare the two best RBs in detail" — expect **multiple `tool_use` blocks in one
  response** (two `get_player_profile` in parallel), the case the batching exists for.

### Phase 4.2 (raw loop) — What I learned

- **An agent is a `while` loop with a `switch` inside — the demystification.** No hidden state
  machine, no framework magic: send transcript, read `stop_reason`, if `tool_use` then dispatch
  by name and append results, repeat until `end_turn`. Having typed the loop, every interview
  question about agent internals is now a question about code I've written.
  - **Interview line:** *"An agent loop is a while loop over the model's stop_reason: on
    tool_use I dispatch the requested calls, append the results, and re-send; on end_turn I
    stop. The model never runs code — it emits a structured request against a schema I publish,
    my Java executes it, and the result re-enters the context. Frameworks automate this loop;
    they don't change its shape."*

- **Parallel `tool_use` blocks return in ONE batched `user` message — the load-bearing
  mechanic.** A single assistant response can carry several `tool_use` blocks (the model
  requesting state and board, or two profiles, at once). All their `tool_result` blocks must go
  back in **one** `user` message, each keyed to its `tool_use_id`. Returning them one-at-a-time
  in separate messages breaks the protocol. Proven live: run 3 fired two blocks on iter 1 and
  two on iter 2, and the loop consumed each pair correctly — including under partial failure,
  where both parallel profile calls errored and both `is_error` results came back batched and
  the model continued.
  - **Interview line:** *"One model turn can request several tools at once, and their results
    must return in a single user message, each tagged with its tool_use_id — not sequentially.
    I proved my loop batched correctly even when both parallel calls failed: the parallel path
    and the error path composed on the first try, because both are just 'collect every block,
    answer every block'."*

- **Tool results re-enter under the `user` role — there is no third role.** The protocol models
  my executing Java as the "user" answering the model's question. That framing is *why* the
  transcript grows: every round trip re-sends the entire accumulated history, tool results
  included.
  - **Interview line:** *"Tool results aren't a separate channel — they re-enter as a user
    message. The wire protocol has two roles; my code plays 'user' when it answers the model's
    tool request. That's also why context grows every iteration: the whole transcript, results
    and all, is resent each round trip."*

- **Token growth is driven by tool-result size, and it's a per-round-trip tax, not a one-time
  cost — quantified.** Same prompt shape, two board sizes: a 20-row default board pushed iter-2
  input to 2217 tokens; a 2-row board pushed it to 1514 — the ~700-token gap *is* the 18 extra
  rows. Because the transcript resends every turn, an oversized tool result inflates the floor
  for the rest of the conversation. Run 3's entire 3-iteration transcript (cumulative 4652) was
  barely larger than run 1's 2-iteration one (3200) — the small board bought a whole extra
  round trip for almost free. This is the compact-DTO discipline proven with numbers.
  - **Interview line:** *"An oversized tool result isn't a one-time cost — the transcript
    resends every iteration, so a 20-row board versus a 2-row board is a tax paid on every
    subsequent round trip. Compact tool DTOs and sane default limits are token-budget
    engineering; I measured the delta rather than asserting it."*

- **`is_error: true` degrades toward noisy at the reasoning layer — the model refuses to
  fabricate.** I killed read-option mid-loop (between iter 1 and 2 of the RB comparison). Both
  profile calls returned `is_error: true`; the loop continued instead of crashing; and the
  model **opened with the missing data** ("detailed profiles are unavailable — server error on
  both"), fell back to the board data it still had, and quarantined the lost pieces under an
  explicit "cannot confirm" list rather than inventing profile stats. Same loud-failure
  asymmetry proven at the persistence layer in 4.1 (loud 500 over wrong 409), now proven at the
  reasoning layer: the gap is visible in the answer, not papered over with a plausible number.
  - **Interview line:** *"I surface tool failures to the model as an error result, not a Java
    exception — is_error: true re-enters the context so the model can react. I killed the
    backing service mid-loop; the agent announced the missing data, fell back to what it had,
    and refused to fabricate the stats it lost. A model that invents numbers when a tool fails
    is the agentic equivalent of a batch job posting wrong ledger entries."*

- **Scope facts are Java-owned, not tool parameters — snapshot-vs-input at the tool contract
  layer.** `sessionId` and `scoringFormat` are bound in the client constructor; the model has
  no field to write them into. If `format` were a tool parameter the model could fetch a player
  under one scoring format while the board it just read used another — two "projected points"
  for the same player, disagreeing, inside one context. Same single-source rule from the
  `teamCount` fix, applied to the tool surface: the caller (here, the model) can't supply a
  fact the session already owns.
  - **Interview line:** *"Identity and scope facts — sessionId, scoring format — are bound
    server-side, never exposed as tool parameters. The model requests data; it doesn't get to
    choose which league or which format that data is scored under. It's the snapshot-vs-input
    rule pushed down to the tool contract: no field for the caller to write a fact the session
    already owns."*

- **Survivability is counted in picks, not opponents — the wheel folds two picks into one gap
  entry.** Live state verified the snake arithmetic: team 8 in a 12-team league, state at
  overall pick 12, `picksUntilUserNextTurn = 5`. The gap is picks 12–16 (my next is 17), and
  `gapTeams` encodes it as team 12 with `picksInGap: 2` (both sides of the wheel) plus teams
  11, 10, 9 at 1 each — four *entries*, five *picks*. A naive "distinct teams in the gap" would
  miscount the board drain as four. The DTO counts picks because that's the denominator that
  matters: how many players leave the board before my turn.
  - **Interview line:** *"The team at the snake turn drafts back-to-back, so a four-opponent gap
    can be five picks of board drain. My gap DTO counts picks, not managers, and folds the wheel
    team's two selections into one entry with a pick count — because survivability is about how
    many players leave the board before my next turn, not how many distinct people pick."*

- **Never read latency off a profiler/debugger run.** Run 3's iter-1 logged 120,110 ms; the
  same call clean-ran in ~2.3 s. The 120 s was the async-profiler + debugger launch
  (`suspend=y`, JFR wall-clock sampling), not real latency. Clean runs showed the true shape:
  first call cheap (~2.3–3.4 s), later calls slower as output grows and the transcript enlarges.
  - **Interview line:** *"One of my latency numbers was 120 seconds on a call that clean-runs in
    two — it was measured under the profiler. Observability that changes the thing it measures
    is worse than none; I read latency under production launch conditions, not under the
    debugger."*

- **Extraction-vs-invention held under pressure — a legitimate inference is not a
  fabrication.** In the RB comparison the model inferred "injury-shortened seasons" from
  `gamesPlayed` values of 7 and 4 — an inference *from data it had*, not invention — and still
  flagged current injury status, depth-chart role, and 2026 news as explicitly missing. The
  boundary isn't "never infer"; it's "never originate a fact the data can't support," and it
  held even when the answer wanted more than the data offered.

- **Honest starvation is the requirements list for 4.3/4.4 — captured verbatim.** Every run
  named what it lacked instead of inventing it: *"I don't have current injury status, depth
  chart updates, or 2026 offseason news."* That sentence, produced by a deliberately
  information-starved agent, *is* the spec for 4.3 (SQL retrievers: teams, schedule, depth
  charts) and 4.4 (vector news RAG). The Phase 2 arc re-ran on purpose: let the starved agent
  fail visibly, and let the failure specify what to retrieve next.

### Phase 4.2 (raw loop) — Mistakes & lessons

- **I launched run 3 under the profiler and briefly believed a 120-second latency.** Caught it
  only by diffing the JVM args against the clean runs. Lesson: capture the launch conditions
  alongside the numbers, or a measurement artifact reads as a finding.
- **Bare `null` in the error message.** `e.getMessage()` on a `ConnectException` is often null,
  so the model (and my log) saw `read-option unreachable: null`. Cosmetic in the playground, but
  the product client should fall back to the exception class name
  (`e.getClass().getSimpleName() + ": " + e.getMessage()`) so a failure is never nameless.
- **I ran on the `STANDARD_6PT` format default instead of wiring the format explicitly.** Safe
  *only because* my test league resolves to standard scoring — the profile numbers and the board
  numbers happened to be on the same scale. In a PPR/custom league that default would silently
  hand the model two disagreeing point scales inside one comparison table, with no error firing.
  That's the quiet cousin of the `teamCount` flaw: not a value in two places, but a fact (the
  league's format) with a silent fallback that masks its own absence. Playground-acceptable;
  a real risk on the agent data path.
- **"No logs in read-option" ≠ tools didn't run.** Spring Boot doesn't log requests by default;
  the console `tool_use` lines plus internally-consistent 2-decimal VORP values (McCaffrey
  127.12, St. Brown 89.84) were the proof the calls landed. The playground console *was* the
  observability this run — which is itself the argument for adding request logging on the
  tool-serving endpoints in the product build.

### Phase 4.2 (raw loop) — To revisit / carried into the product spec

- **Player-detail tool must score under the session's resolved rules — no format default on the
  agent data path.** The public `/profile` endpoint keeps its human-friendly `STANDARD_6PT`
  default; the agent's tool wraps a league-aware service method that takes the resolved rules as
  a **required** argument. Two contracts over the same data, each honest for its caller. (Also:
  the preset-keyed profile can't exactly match a *custom* league's board — another reason the
  tool wraps the service, not the controller.)
- **Exception-name fallback in the error result** — no bare `unreachable: null`.
- **Request logging on read-option's tool-serving endpoints** — so the agent path is observable
  from both ends: the loop logs the `tool_use`, read-option logs the request it received. When
  the two disagree, I want both sides of the wire.
- **Output-shape constraint in the product system prompt** — the raw loop returned heavy
  markdown with emoji headers; fine for a console smoke test, wrong for a fast "on the clock"
  conversational turn.
- **Next in 4.2:** the 30-minute Spring AI default-execution spike (same three tools via
  `@Tool`, internal execution on — watch the schema, the parsing, and the loop all disappear and
  name what the framework took over), then the product build in read-option (user-controlled
  execution via `internalToolExecutionEnabled(false)` / `ToolCallingManager`, hybrid
  pre-injection context, per-session message-window `ChatMemory`) — with a five-minute
  verification of the 1.1.6 tool-API signatures before any spec is written.

### Phase 4.2 (raw loop) — Concepts Cheat-Sheet (additions)

**The agent loop (owned by hand)**
- An agent is a `while` loop over `stop_reason`: `tool_use` → dispatch + append results + resend;
  `end_turn` → stop; anything else → throw. Frameworks automate the loop, not its shape
- Tool results re-enter under the `user` role — there is no third role; my executing Java plays
  "user" when it answers the model's tool request
- One assistant turn can carry **multiple** `tool_use` blocks (parallel); **all** results return
  in **one** batched `user` message, each keyed to its `tool_use_id` — sequential return breaks
  the protocol. The parallel path and the error path are the same code: answer every block
- Tool failures return as `tool_result` with `is_error: true` (data the model reacts to), not as
  Java exceptions (reserved for protocol failures — non-200 from the API, loop cap)
- A `MAX_ITERATIONS` cap that throws loud is the backstop against a model that never reaches
  `end_turn`

**Token economics of tool calling**
- The transcript resends every iteration, so tool-result size is a **per-round-trip tax**, not a
  one-time cost — a 20-row vs 2-row board was a ~700-token/iteration difference, empirically
- Compact tool DTOs and sane default limits are token-budget engineering; measure the delta,
  don't assert it
- Latency lives in the sequential round trips, not the DB; never measure it under a
  profiler/debugger launch (a 2 s call read as 120 s)

**Tool contract discipline**
- Scope/identity facts (sessionId, scoring format) are server-bound, never tool parameters —
  snapshot-vs-input pushed down to the tool surface; the model requests data, it doesn't choose
  the league or format that data is scored under
- Survivability counts **picks, not opponents** — the snake wheel folds a turn team's two picks
  into one gap entry with a pick count; distinct-teams would undercount board drain
- Extraction-vs-invention holds under pressure: inferring from data you have (games played →
  shortened season) is legitimate; originating a fact the data can't support is not — and the
  model still flags what it genuinely lacks
- A deliberately starved agent's "I don't have X" is not a defect to suppress — it's the
  verbatim requirements list for the next retrieval increment

### Phase 4.2 — The Agent Loop, Part 2: Spring AI Default-Execution Spike ✅ COMPLETE

A 30-minute throwaway spike in `spring-ai-playground`, run *immediately after* the raw
loop so the contrast was fresh. Same three tools, same three prompts, same system prompt —
but this time the tools are `@Tool`-annotated methods handed to a `ChatClient` with
**internal (default) tool execution left on**. The entire deliverable is the *understanding*
of what the framework took over, and — more sharply — what it hid. Deliberately the
opposite configuration from the product build (which disables internal execution); the
spike exists to make the trade-off felt before it's chosen.

### Phase 4.2 (spike) — What I built (concrete)

- **`DraftTools`** — a plain class (not a Spring bean) with three `@Tool`-annotated methods
  wrapping the same read-option endpoints. `sessionId` and `scoringFormat` are
  **constructor fields, not method parameters** — see the field-vs-parameter lesson below.
  Tool errors returned as a plain `"ERROR ..."` string (with the exception-class-name
  fallback lesson carried from the raw loop), not thrown.
- **`SpikeRunner`** — a `CommandLineRunner` that builds a `ChatClient` from the injected
  `ChatClient.Builder` (`.defaultSystem(...)`, `.defaultAdvisors(new SimpleLoggerAdvisor())`),
  then fires the three prompts, printing `.content()` and aggregate token usage per prompt.
- **Config plumbing:** `server.port=8081` (the Boot app's own Tomcat collided with
  read-option on 8080), `logging.level.org.springframework.ai.chat.client.advisor=DEBUG`
  to surface the advisor logs, plus the Spring AI bump to **1.1.8** (CVE patch — see below).

### Phase 4.2 (spike) — What I learned

- **The whole hand-written loop collapses into `.tools(obj).call()`.** Everything I typed in
  the raw loop — the `TOOLS_JSON` schema, the `callClaude` HTTP plumbing, the `stop_reason`
  switch, the content-block extraction, the `tool_result` batching, the `messages`
  accumulation, the `for` + `MAX_ITERATIONS` cap — is gone, replaced by attaching a tool
  object to the prompt. The framework's `ToolCallingAdvisor`/`ToolCallingManager` runs the
  recursive loop internally.
  - **Interview line:** *"Spring AI's default tool execution is the CrudRepository of agent
    loops — I annotate methods, hand the object to the ChatClient, and the framework runs
    the whole multi-round-trip loop. Every piece I hand-wrote in the raw exercise — schema,
    stop_reason switch, result batching, the loop itself — becomes framework-internal. It's
    perfect until the day I need to see the SQL."*

- **The schema is generated from the method signature — drift killed by construction.** In
  the raw loop the `TOOLS_JSON` was a second source of truth alongside my Java, free to
  drift. `@Tool`/`@ToolParam` make the **signature the schema**, so they cannot disagree —
  the same move as the Phase 3 spec→resolver type boundary and the Phase 4.1 snapshot rule,
  now applied to the tool contract. `@ToolParam` params are required by default; nullable →
  optional.
  - **Interview line:** *"A hand-written tool schema is a dual source of truth with the Java
    method it describes — they drift. Spring AI generates the schema from the method
    signature, so the schema and the code are the same artifact. It's the same drift-killing
    move as freezing a config fact as a snapshot: one owner, enforced by structure, not
    discipline."*

- **`@Tool` turns every parameter into schema — so server-owned facts must be FIELDS, not
  parameters.** This is the Spring AI-specific mechanism for the same rule I've applied
  since Phase 3. If `sessionId` or `scoringFormat` were method parameters, the model would
  get a schema field it could fill — it could address another session or rescore under a
  different format. Making them constructor fields means the generated schema has no such
  field: the model *cannot* write the wrong session, enforced by the signature.
  - **Interview line:** *"In Spring AI, @Tool turns every method parameter into schema the
    model can fill, so the way I keep a fact server-owned is to make it a constructor field,
    not a parameter. My draft tools take sessionId and scoring format as fields — the model
    literally has no parameter to address a different session or rescore under another
    format."*

- **The tool loop ran entirely BENEATH the advisor chain — and `toolCalls: []` does not mean
  no tools ran.** `SimpleLoggerAdvisor` fired exactly **once** per prompt (one `request:`,
  one `response:`), and every logged response carried `finishReason: end_turn` with
  `toolCalls: []`. That empty list is the trap: the tools *did* run (the answers contain my
  exact engine numbers — St. Brown VORP 89.8, McCaffrey 127.12, McBride 70.85, and profile
  history rows that only `get_player_profile` returns), but by the time the advisor sees the
  response the model has already finished the tool loop and produced its final `end_turn`
  turn, so no calls are *pending*. One advisor firing = the loop runs below the advisor
  chain, invisibly.
  - **Interview line:** *"toolCalls: [] on the final response doesn't mean no tools were
    used — it means none are pending. The framework already executed them and looped back to
    end_turn below my visibility. Reading that correctly is the difference between 'Spring AI
    ignored my tools' and 'Spring AI ran them where I can't watch.'"*

- **Token attribution is GONE — one aggregate number, and the numbers prove the loss.** In
  the raw loop I had a per-iteration growth curve and could point at the 20-row board as the
  700-token culprit. Here I got one `usage` per exchange, and across the three prompts:
  prompt 1 = 3322 prompt tokens, prompt 2 = **6580**, prompt 3 = 4796. Prompt 2 ("TE now or
  wait") cost nearly **double** prompt 1 and *more* than prompt 3 — even though prompt 3
  does strictly more work (two profile calls on top of state+board). The model made
  larger/more board calls under the hood on prompt 2, but I **cannot attribute it** — the
  log shows no `tool_use`. Worse, `promptTokens=3322` is large enough to look like the
  **final round trip only**, not the sum across the internal loop — so my true spend may be
  higher than the number shown and I can't see it.
  - **Interview line:** *"Default execution gives one aggregate usage number per request. In
    my spike, the 'wait or reach' prompt cost double the 'who to pick' prompt and more than
    the two-profile comparison — the model made bigger tool calls under the hood, but I
    couldn't attribute a single token to a single call, and the number looked like the final
    leg only. That opacity is exactly what I need visible on the draft clock, so my product
    path takes the loop back."*

- **The framework changed my CODE, not the agent's BEHAVIOR.** The answers were
  qualitatively identical to the raw loop — same chaining (board → playerId → profile), same
  parallel calls (inferable from token size), same honest starvation ("I don't have current
  injury status, depth chart changes, or offseason news"). Proof the framework runs the
  identical loop I hand-wrote; it changed what I *type* and what I can *see*, not what the
  model *does*.

### Phase 4.2 (spike) — Mistakes & lessons

- **Predicted both outcomes, got the diagnostic one.** The spec called out that
  `SimpleLoggerAdvisor` would fire either once (loop below the advisor) or N times (advisor
  inside the loop), and what each would mean. Getting one firing isn't luck — it's the
  default-execution architecture. But the value is that I now have the *log* that proves it,
  which beats having been told. Lesson: design the observation so the result is
  interpretable either way *before* running it.
- **Two environment gotchas, both non-code.** (1) The Boot app's embedded Tomcat collided
  with read-option on 8080 — a bare `main` (raw loop) only opens outbound connections and
  shares the port fine, but a second Boot web app binds a server socket on startup;
  `server.port=8081` relocated it. The tool calls to 8080 were always correct. (2) An
  existing Phase 0 `CommandLineRunner` (`ChatRunner`) would double-fire on boot alongside
  `SpikeRunner` — quiet the old one for the spike.
  - **Interview line:** *"A bare Java main and a Spring Boot app collide differently on
    ports: the plain client only opens outbound connections, so it shares 8080 fine, but a
    Boot web app binds a server socket on startup — two of them is a bind conflict before
    any of my code runs. The fix relocates the second app's own Tomcat; the outbound tool
    calls were never the problem."*

### Phase 4.2 (spike) — The dependency decision (CVE triage, not an upgrade command)

Spring AI 1.1.6 was flagged for vulnerabilities. The lesson is separating two decisions the
flag conflates:
- **Patch the CVE:** 1.1.6 → **1.1.8** is a same-minor patch (fixes CVE-2026-41863 and
  CVE-2026-47835, bumps Spring Boot to 3.5.15). Non-breaking, tool-calling API unchanged,
  every 4.2 spec stands. Done via the **Spring AI BOM** — one version property, all
  `spring-ai-*` artifacts move together. This is the right move now.
- **Adopt the latest (2.0):** Spring AI 2.0 **requires Spring Boot 4.0** (Framework 7,
  Jackson 3, JSpecify null-safety) and reshapes the tool API (builder-only
  `internalToolExecutionEnabled`, `ToolCallAdvisor` as default, new `ToolSpec`). That's a
  whole-project migration, not a dependency bump — scheduled as its own increment, never
  braided into a security patch.
- **The EOL wrinkle:** Spring Boot 3.5 hit **end of life June 30, 2026** — so the 1.1.x line
  (including 1.1.8) is patched but on an EOL baseline. Medium-term pressure toward the Boot 4
  + Spring AI 2.0 migration; not a 4.2 emergency.
  - **Interview line:** *"A CVE flag is a triage question, not an upgrade command. I separate
    'patch the vulnerability' — the smallest in-line patch that clears the CVE and passes the
    suite — from 'adopt the latest,' which is a platform decision with its own risk
    assessment. Here that was a same-minor BOM bump; the jump to 2.0, which drags a whole
    Spring Boot 4 migration, goes on the roadmap as its own increment. It's the banking
    instinct: you don't take a major-version jump to clear a scanner finding."*
  - **Interview line (lifecycle):** *"I track the support lifecycle of my baseline, not just
    my direct dependencies. Spring Boot 3.5 hit EOL June 30, so my patched Spring AI line
    sits on an EOL platform — which makes the Boot 4 / Spring AI 2.0 move a planned near-term
    increment rather than a surprise. Knowing the EOL date turns a migration from an
    emergency into a schedule."*

### Phase 4.2 (spike) — To revisit / carried forward

- **Boot 4 + Spring AI 2.0 migration (post-Phase-4 increment):** the 1.1.8 patch clears the
  CVEs but leaves the project on the EOL Boot 3.5 baseline. Migration reshapes the tool API
  (builder-only `internalToolExecutionEnabled`, `ToolCallAdvisor` default, `ToolSpec`) plus
  Jackson 2→3 and Framework 6.2→7 across the whole project. A named learning phase, not a
  chore — a real CV line.
- **Confirm whether 1.1.8 `usage` reports final-leg-only vs aggregate** across the internal
  tool loop — the spike's numbers suggest final-leg, which would mean true token spend is
  under-reported in default mode. Moot for the product build (the loop becomes mine and I
  count per iteration), but worth knowing.
- **Product build carries (unchanged):** resolved-rules profile tool (no format default on
  the agent data path), request logging on read-option's tool endpoints, output-shape
  constraint in the product system prompt (the spike returned heavy emoji-markdown — wrong
  for a fast on-the-clock turn).

### Phase 4.2 (spike) — Concepts Cheat-Sheet (additions)

**Spring AI tool calling (declarative / default execution)**
- `@Tool(description=...)` on a method + `@ToolParam(description=..., required=...)` on
  params; Spring generates the JSON Schema **from the method signature** — schema and code
  are one artifact, so they can't drift
- Attach per-request: `chatClient.prompt().user(...).tools(toolObject).call()`; Spring turns
  each `@Tool` method into a `ToolCallback`. `@ToolParam` params are required by default;
  nullable → optional
- Default execution runs the whole recursive tool loop internally (`ToolCallingAdvisor` /
  `ToolCallingManager`) — **beneath** the advisor chain
- **Server-owned facts go in constructor FIELDS, not method parameters** — `@Tool` exposes
  every parameter as a fillable schema field, so a field is the only way the model can't
  address it (sessionId, scoring format)
- `SimpleLoggerAdvisor` (`.defaultAdvisors(...)` + `logging.level...advisor=DEBUG`) logs the
  request/response — but in default mode fires **once** per request; `toolCalls: []` on the
  final `end_turn` response means "none pending," not "none used"
- Token usage: `.call().chatResponse().getMetadata().getUsage()` →
  `getPromptTokens()`/`getCompletionTokens()`/`getTotalTokens()` — **aggregate per request,
  not per iteration**; you lose per-call attribution (and possibly see final-leg-only)

**The framework-vs-hard-way ledger (raw loop → default execution)**
- Generated from signature: the tool schema (was hand-written `TOOLS_JSON`)
- Framework-owned: HTTP call, `stop_reason` handling, tool_use extraction, tool_result
  batching, transcript accumulation, the loop + its cap
- **Lost visibility (the cost of the abstraction):** per-iteration token growth, per-call
  attribution, round-trip count, individual `tool_use` blocks, per-round-trip latency, your
  own loop cap — i.e. exactly the instrumentation an on-the-clock advisor needs, which is
  why the product build disables internal execution and drives the loop via
  `ToolCallingManager`

**Dependency hygiene**
- CVE flag = triage: patch in-line to the smallest version that clears it (BOM one-liner),
  keep the major migration a separate, planned increment
- Spring AI is BOM-managed — bump one `spring-ai.version` property, all artifacts align
- Track the **EOL date** of the baseline (Boot 3.5 → June 30 2026), not just direct deps —
  it converts a migration from emergency to schedule
- A Boot `main` shares a port (outbound only); a Boot **web app** binds a server socket on
  startup — the second app needs `server.port` relocated

### Phase 4.2 — The Agent Loop, Part 3: Production Build (user-controlled execution) ✅ COMPLETE

The real deliverable: the conversational draft agent in read-option (`app.readoption.agent`),
built by Claude Code from a chat-authored spec, reviewed as a diff, and verified live against
session 4 — including the database-kill drill. Parts 1 and 2 were the learning scaffold (own
the loop by hand, then feel what the framework hides); Part 3 is the version that keeps the
framework's schema generation and dispatch but takes the loop back for observability. 253 tests
green (28 new).

### Phase 4.2 (product) — What I built (concrete)

- **`DraftAgentService`** — the manual loop: `ChatModel` + `ToolCallingManager` with
  `internalToolExecutionEnabled(false)` on `ToolCallingChatOptions` (the 1.1.x API; verified
  against the 1.1.8 jars with `javap` before writing a line). Owns the `while`, a loud
  iteration cap (`AgentLoopLimitException` → 500 via the global handler), and per-round-trip
  DEBUG instrumentation. **No transaction anywhere near the LLM calls** — each tool opens its
  own short read-only transaction inside the 4.1 services.
- **`DraftAgentTools`** — a per-request POJO (never a Spring bean), three read-only `@Tool`
  methods; `sessionId` and resolved `ScoringRules` are constructor-bound **fields**, never
  `@ToolParam`. A test parses the generated JSON schemas to prove the model has no parameter to
  reach another session or format. Entry/exit DEBUG logs (`tool exec -> / <-`) log the raw
  model-supplied args (pre-normalization) so a bad-args throw leaves the diagnostic orphaned
  arrow.
- **`ProfileScoringService` + `PlayerProfileView`** — history + projection scored under the
  session's resolved rules (never the `STANDARD_6PT` default the public profile endpoint uses);
  ADP via the same `AdpBucket` mapping as the board. Resolves the carry item: the agent's
  profile tool and the board never quote different markets.
- **`AgentPromptBuilder`** — externalized template (`prompts/draft-agent-system.txt`) + a
  pre-injected league scoring summary + the user's `DraftTactics`. Snapshot facts pre-injected;
  dynamic facts (roster, board) left to tools. `teamCount`/`totalRounds` printed from the
  **session snapshots**, not re-derived from config.
- **`AgentConfig`** (session-keyed `MessageWindowChatMemory`, only user turn + final advice
  persisted), `AgentProperties` (model, max-iterations 8, memory-window 20), controller
  `POST /api/draft/sessions/{id}/advise`, DTOs, `AgentLoopLimitException`.

### Phase 4.2 (product) — What I learned

- **User-controlled execution is the raw loop, wearing the framework's schema + dispatch.** The
  1.1.x pattern — `chatModel.call` → `while (response.hasToolCalls())` →
  `toolCallingManager.executeToolCalls(prompt, response)` → rebuild the prompt from
  `result.conversationHistory()` → call again — maps 1:1 onto the hand-written raw loop.
  `hasToolCalls()` is the `stop_reason==tool_use` check; `executeToolCalls` is the content-block
  dispatch + batching; `conversationHistory()` is the message accumulation. The framework kept
  the drift-killers (schema from signatures, dispatch) and handed back the loop, the cap, and
  the token accounting.
  - **Interview line:** *"For the production agent I disabled Spring AI's internal tool
    execution and drove the loop myself with ChatModel and ToolCallingManager — call the model,
    while it has tool calls execute them via the manager and feed the conversation history back,
    until it stops. I kept the framework's schema generation and dispatch but took back the
    loop, because loop caps, per-iteration token growth, and latency budgets have to be
    observable when the advice happens on the draft clock."*

- **`internalToolExecutionEnabled(false)` is 1.1.x-only — removed in 2.0.** Verified before
  writing: 2.0 removes the option from `ToolCallingChatOptions` and every provider options class,
  replacing it with `AdvisorParams.toolCallingAdvisorAutoRegister(false)`. So the CVE patch that
  kept the project on 1.1.8 is also what keeps this loop's core API valid — a major-version jump
  for the vuln would have forced rewriting the loop mid-feature. The verification retroactively
  justified the dependency call.
  - **Interview line:** *"My agent's core API — disabling internal tool execution on the chat
    options — is Spring AI 1.1.x-specific and was removed in 2.0. When a CVE flag pushed toward
    upgrading, I patched in-line within 1.1.x rather than jumping to 2.0, because the major
    version would have forced a loop rewrite and a Spring Boot 4 migration mid-feature."*

- **No transaction around the loop — a deliberate anti-pattern avoided.** The advice loop holds
  multi-second LLM round trips; each tool opens its own short read-only transaction inside the
  4.1 services (READ → REASON, no txn spanning the model calls). A transaction wrapped around
  the loop would pin a DB connection across every round trip — under load, pool exhaustion. Same
  anti-pattern as a transaction spanning a remote call in a payment flow.
  - **Interview line:** *"My agent loop is deliberately non-transactional — each tool opens its
    own short read-only transaction inside the domain services. A transaction wrapped around the
    loop would hold a DB connection across multi-second LLM round trips; that's the same
    anti-pattern as a transaction spanning a remote call in a payment flow, and under load it
    exhausts the pool."*

- **The field-vs-parameter rule became a regression-tested safety property.** `sessionId` and
  `scoringRules` are constructor fields, so the generated tool schema has no field for them. A
  test parses the emitted JSON schemas and asserts each tool exposes ONLY its documented params
  (state: none; board: position, limit; profile: playerId) and contains neither `sessionId` nor
  `scoringRules`. The snapshot-vs-input boundary is now enforced by a test, not just by intent.
  - **Interview line:** *"Session and scoring format are bound as constructor fields on the
    tools object, so the model's generated schema has no parameter to address another session or
    rescore under another format — and a test parses the emitted schema to prove it. The
    boundary is a regression-tested safety property, not a convention."*

- **Fixing a two-sources bug: the regression test makes the sources disagree.** When the prompt
  builder was re-deriving `teamCount`/`totalRounds` from config instead of reading the session
  snapshots (the 4.1 teamCount principle resurfacing in the prompt layer), the fix came with a
  test that feeds a session snapshot (14 teams / 16 rounds) deliberately disagreeing with the
  config (12 teams / 15 derived) and asserts the snapshot wins — so re-derivation can never
  again pass by coincidence.
  - **Interview line:** *"When I fix a two-sources bug, the regression test uses a fixture where
    the two sources deliberately disagree — if they agree, the test can pass for the wrong
    reason. My prompt-builder test feeds a session snapshot of 14 teams against a config that
    derives 12, and asserts the snapshot wins."*

- **Pre-inject vs tool is a latency-and-freshness decision — validated in one line of output.**
  Static session facts (scoring rules, `DraftTactics`) are pre-injected into the system prompt;
  dynamic facts (state, board, profiles) are tools. The clean run cited "Your Zero RB strategy"
  in its reasoning — the tactics reached the model through pre-injection, zero tool calls spent
  fetching strategy, and the model wove it in. The hybrid decision proven in the transcript.
  - **Interview line:** *"Not every fact should be a tool. Snapshot facts — scoring rules, the
    user's standing draft tactics — I pre-inject into the system prompt once, because a tool
    call is a model round trip and round trips are latency I pay on the clock. Tools are for what
    changes as the draft drains. The agent cited the user's Zero-RB strategy in its advice
    without spending a tool call to fetch it."*

- **Memory keyed on the same server-owned session id, tool traffic excluded.** Session-scoped
  `MessageWindowChatMemory`; only the user turn + final advice persisted, never the intra-loop
  tool requests/responses (they'd bloat the window and leak schema into history). The cap test
  also proves a failed loop persists nothing. Live: a follow-up turn recalled the prior
  recommendation with zero tool calls.
  - **Interview line:** *"The agent's memory is keyed on the same server-owned session id that
    scopes every tool, and I persist only the user's question and the final advice — the
    intra-loop tool traffic stays out, because persisting it would bloat the window and leak the
    tool schema into conversation history."*

- **The database-kill drill — loud failure at the reasoning layer, under PARTIAL failure.**
  Killed Postgres mid-advice. `getDraftState` threw a `JpaSystemException` deep in the tool; the
  `DefaultToolCallingManager` caught it, converted it to an error tool-response, and the loop
  CONTINUED — Hikari replaced the broken connection and the very next tool (`getDraftBoard`)
  succeeded on a fresh one. The request never crashed. The advice opened with "I'm running into
  a system error right now and can't pull a live update," fell back to prior-context players, and
  flagged the pick as unconfirmed rather than fabricating a board state. Stronger than the drill
  anticipated: it proved honest degradation under *partial* tool failure (one tool down, one up),
  the more common real-world case, not just total outage.
  - **Interview line:** *"I killed the database mid-advice on the live agent. One tool threw, the
    tool manager converted the exception into an error tool-response, and the loop continued — a
    second tool ran on a recovered connection and succeeded. The model degraded honestly: it
    opened with 'I can't pull a live update,' fell back to prior context, and flagged the pick as
    unconfirmed rather than inventing a board state. Loud failure at the reasoning layer, under
    partial tool failure."*

### Phase 4.2 (product) — Mistakes & lessons

- **The spec was wrong in three places; the delegate was right.** Claude Code deviated with
  reasoning on each: `String playerId` not `long` (Player.id is String domain-wide), `DraftBoardView`
  not `List<PlayerValue>` (the view carries the name/VORP/ADP the tool description promises;
  PlayerValue doesn't), and a no-op commit-1 (board logic already lived in `DraftBoardService`).
  Lesson: a spec is a design intent, not gospel — a delegate that checks a deviation against the
  stated rule (don't-collapse-boundaries) and reports it is doing the job right. Review the diff,
  accept the better call.
- **The snapshot re-derivation slipped into the prompt builder.** Caught in review, not by tests
  (they passed because the fixture agreed) — the exact shape of the 4.1 teamCount bug, one layer
  up. Reinforces: a passing suite where every fixture happens to agree is not proof; the review
  is the catch.
- **Spec §7 called in-process tool logging "moot"; the review overrode it.** The service logs
  what the model *requested*; without tool entry/exit logs nothing records what *executed*. The
  kill drill depended on exactly that both-ends visibility (the orphaned arrow). Lesson: "moot"
  was wrong — request-side and execution-side logs answer different questions, and the failure
  case needs both.

### Phase 4.2 (product) — To revisit / deferred (added to project instructions on closure)

- **`record_pick` as an agent tool** — mutation via the agent with a confirmation turn. 4.2 is
  read-only by design.
- **JDBC-backed `ChatMemory`** — survive restarts / multi-instance. In-memory is fine for a
  single draft sitting.
- **Boot 4 + Spring AI 2.0 migration** — a named increment: rewrites the loop to
  `AdvisorParams.toolCallingAdvisorAutoRegister(false)`, drags Jackson 2→3, Framework 6.2→7,
  JSpecify. The 1.1.x line is patched (1.1.8) but on the EOL Boot 3.5 baseline (EOL June 30 2026).
- **`countBetterAdpAtPosition` `ELSE adpPpr` branch** — an unrecognized bucket string silently
  scores as PPR. Unreachable today (callers pass a three-value enum's `name()`); a quiet-default
  smell to make explicit if a fourth bucket ever appears.

### Phase 4.2 (product) — Concepts Cheat-Sheet (additions)

**User-controlled tool execution (Spring AI 1.1.x)**
- `ToolCallingManager.builder().build()` + `ToolCallingChatOptions.builder().toolCallbacks(ToolCallbacks.from(pojo)).internalToolExecutionEnabled(false).build()`
- Loop: `chatModel.call(prompt)` → `while (response.hasToolCalls())` →
  `toolCallingManager.executeToolCalls(prompt, response)` →
  `prompt = new Prompt(result.conversationHistory(), options)` → call again → final text
- `conversationHistory()` carries system+user+tool-calls+tool-results forward — never re-add the
  system message; own the iteration cap (loud) and per-call token/latency logging
- **1.1.x-only:** removed in 2.0 (`AdvisorParams.toolCallingAdvisorAutoRegister(false)` replaces it)
- Tool exceptions are caught by `DefaultToolCallingManager` and returned to the model as error
  tool-responses — the honest-degradation path, no crash; verified by killing the DB mid-loop

**Agent architecture discipline**
- No transaction around the loop — each tool opens its own short read-only txn; a txn spanning
  the LLM round trips pins a connection and exhausts the pool under load
- Server-owned facts (session, rules) are constructor FIELDS on the tools object, never
  `@ToolParam` — enforced by a schema-parsing test, a regression-tested safety property
- Pre-inject snapshot facts (scoring, tactics); tool the dynamic ones (state, board, profiles) —
  a latency-and-freshness decision, not a default
- Memory keyed on the server-owned session id; persist only user turn + final advice, never tool
  traffic
- Read-only tool surface in v1 — the narrowest stored-proc surface for an untrusted caller;
  mutation earns its own increment with confirmation

**Reviewing a delegated build**
- The spec is design intent, not gospel — a delegate that deviates WITH reasoning against a
  stated rule is doing the job right; review the diff, accept the better call
- A passing suite where every fixture agrees is not proof of a single-source invariant — the
  review is the catch; the regression test should make the two sources disagree
- Request-side and execution-side logs answer different questions — an agent needs both, and the
  failure case (orphaned request with no execution) is where both-ends logging earns its keep

### Phase 4.3 — Current-Context Ingestion (SQL Retrievers) ✅ COMPLETE

The structured half of the 4.2 starvation list — teams, schedule, depth charts, injury
status — landed as deterministic SQL retrieval flowing through the EXISTING tools. No new
Spring AI surface: the increment widened what flows through the agent, not the agent
itself. Four commits (A–D), designed here from an empirical source audit, built by Claude
Code, reviewed as diffs. 287 tests green at close (34 new over 4.2's 253).

### Phase 4.3 — What I built (concrete)

- **The source audit came FIRST** — payload profiling before any DDL. `jq` distinct-value
  profiles over the Sleeper blob and a live ESPN schedule payload decided the schema:
  32 raw `depth_chart_position` values (incl. `LWR`/`RWR`/`SWR` — the receiver split),
  9 `injury_status` values, **33 team codes for 32 teams** (stale `OAK` on active
  players), **2,199 active players with NULL team** (the free-agent pool — two-thirds of
  actives), and exactly one abbreviation divergence between providers (Sleeper `WAS` /
  ESPN `WSH`).
- **V13** — `nfl_team` (Sleeper abbrev PK = canonical, `espn_abbrev` crosswalk column,
  derived `bye_week`), `team_schedule` (team-week rows, PK `(team, season, week)`, no FK
  — landing semantics, each game twice), five raw-vocabulary columns on `player`
  (`depth_chart_position`, `depth_chart_order`, `injury_status`, `injury_body_part`,
  `injury_notes`). **V14** — the 32-team seed, no OAK, WAS/WSH the single crosswalk row.
  DDL/DML split deliberate (schema change vs reference-data load — bank release-script
  discipline).
- **The depth chart is a query, not a table.** Two columns on `player` + `WHERE team = ?
  AND depth_chart_position = ? ORDER BY depth_chart_order`. A team-shaped depth chart
  would be a repeating group (1NF) and would duplicate player→team membership the player
  row already owns.
- **`EspnScheduleClient`** (site API host — no `X-Fantasy-Filter`, its own small client)
  + **`TeamScheduleSyncService`**: hard `seasonType.type == 2` filter (preseason weeks
  collide with the PK and poison bye derivation), ESPN→Sleeper crosswalk applied ONCE at
  the write boundary (unknown abbrev throws — vocabulary surprises never land),
  delete-and-reload per `(team, season)`, **loud bye derivation** (exactly 17 rows +
  exactly one missing week in 1..18, else null + WARN — an absent bye is recoverable, a
  wrong one poisons every roster read). **`TeamScheduleWriter`** as a separate bean so
  the `@Transactional` proxy applies — READ (HTTP, no txn) → WRITE (one short txn per
  team), the reconciliation phasing at sync scale.
- **`TeamContextService`** — the single home of the degradation vocabulary and all
  player→team context reads. LEFT-JOIN posture everywhere: null team, unknown team
  (stale OAK), missing bye, unsynced schedule each produce a DISTINCT loud string —
  never a dropped row, never a silent OAK→LV remap.
- **`PlayerProfileView` role block** — raw depth position + order, `depthChartAhead`
  scoped to the RAW sub-position ladder (an order-2 SWR sees only the order-1 SWR, never
  the LWR/RWR starters), the status-gated injury trio, bye + weeks-1–3 opponents.
  **`DraftStateView` roster entries gained bye weeks** — shared-bye risk visible without
  a new tool. Tool descriptions updated to advertise the new facts.
- **Commit D (correctness, own commit):** the merge-based player upsert was nulling
  every column the Sleeper blob doesn't carry — `espn_id` (reported) AND `created_at`
  (found by auditing the mechanism). Fix: carry non-source-owned columns forward from
  the existing row; regression test seeds both and asserts both survive a plain sync.
- **Sync + verification:** 32/32 teams synced, 17 weeks each, all byes derived;
  draftable-board coverage profiled BEFORE judging the agent (409 mart rows: 20 no-team,
  21 no-depth, 58 with injury status, 8 without espn_id).

### Phase 4.3 — What I learned

- **Audit the source empirically before designing the schema — profile columns, not
  sample rows.** Three sample players can't prove a vocabulary is closed; the source's
  `SELECT DISTINCT` (a `jq unique` over the blob) can. The audit killed a wrong design
  (depth chart on a team table), found the crosswalk row, and surfaced two dirty-data
  populations before they could become agent-quality mysteries.
  - **Interview line:** *"When a landing column's vocabulary decides the design, I
    profile distinct values, not sample rows — my audit found 33 team codes for 32
    teams and a receiver vocabulary that split one fantasy position into three ladders.
    Both findings were schema-shaping; neither was visible in sample rows."*

- **Dirty source data is a certainty; the design decides WHERE it surfaces.** The write
  side throws loud on a code it can't crosswalk; the read side LEFT-JOINs and degrades
  to explicit "unavailable" strings. An INNER JOIN would have silently dropped every
  free agent and stale-OAK player from profiles — the worst outcome, quiet.
  - **Interview line:** *"I treat unknown vocabulary asymmetrically by boundary: the
    write side throws loudly on a code it can't crosswalk, the read side LEFT-JOINs and
    degrades to 'context unavailable' instead of dropping rows. Dirty data will arrive;
    the design decides whether it surfaces visibly or vanishes silently."*

- **Landing columns store the source's raw vocabulary; normalization is driven by the
  READER, not by tidiness.** The consumer of `injury_status` and `depth_chart_position`
  is the LLM, which reads `IR`, `PUP`, `SWR` natively — an enum would be a translation
  layer with no reader on the other side. The graduation trigger: the first time JAVA
  logic must branch on a value (it fired in 4.3.1 — see below).
  - **Interview line:** *"I normalize a landing column to a domain enum only when Java
    logic branches on it. My injury and depth columns feed an LLM, which reads the
    source vocabulary natively — normalization is driven by the reader, and until 4.3.1
    there was no Java reader."*

- **Hibernate flushes in a FIXED action order — inserts before deletes — so
  delete-and-reload with identical composite PKs collides under a derived `deleteBy`.**
  Two mechanics conspire: the ActionQueue runs INSERTs before entity DELETEs regardless
  of call order, and a derived delete loads entities into the persistence context first
  (select-then-remove), so old and new rows with the same identifier collide as
  `NonUniqueObjectException`. The bulk JPQL `@Modifying` delete executes immediately as
  SQL, bypassing both — which is exactly why `flushAutomatically` (push pending changes
  down first) and `clearAutomatically` (detach stale managed rows after) exist. Caught
  live by the `@DataJpaTest` re-sync case against the real container. Banking analogy:
  a direct SQL purge doesn't consult the end-of-day posting queue — flush the queue
  before, reconcile after.
  - **Interview line:** *"Hibernate flushes in a fixed action order — inserts before
    deletes — so delete-and-reload with identical primary keys collides under a derived
    delete. I use a bulk JPQL delete, which executes immediately and bypasses the
    persistence context, with flushAutomatically and clearAutomatically keeping the
    context honest on both sides of the bypass, and an integration test that re-syncs
    the same keys against the real database."*

- **JPA merge copies the detached entity's ENTIRE state — null is a value like any
  other.** Every field the source payload doesn't carry must be explicitly carried
  forward from the existing row, or it's silently nulled on every re-sync. `Persistable`
  controls WHICH path (persist vs merge); it does nothing about WHAT merge copies.
  Proven by a production natural experiment: before/after sync counts showed exactly
  3,217 of 3,221 rows (the pre-existing population) lost `created_at`, while the 4 new
  rows kept it — and `updated_at` survived everywhere because `@PreUpdate` re-derives it
  on every write. Two maintenance mechanisms, two different fates.
  - **Interview line:** *"JPA merge copies the detached entity's entire state onto the
    managed row — null included. My sync built fresh entities from the source payload,
    so every column the source doesn't carry was silently nulled on each re-sync. I
    proved it with a before/after count — exactly the pre-existing rows lost the field
    — and the fix enumerates the non-source-owned columns and carries each forward."*

- **When fixing a bug, audit the MECHANISM's blast radius, not the symptom's field.**
  The reported bug was "espn_id gets nulled"; the mechanism was "merge overwrites every
  column the blob doesn't source." Enumerating the non-source-owned columns found the
  second casualty (`created_at`) before it was ever reported.
  - **Interview line:** *"When I fix a bug, the blast radius I audit is the mechanism's,
    not the symptom's. The reported field was one of two — the same merge that nulled
    the crosswalk id had been nulling the creation timestamp on every sync, silently,
    because the column was nullable and nothing read it."*

- **Defensive code is split by WHO OWNS the invariant.** An invariant my own types
  enforce gets documented, not defended (the `ELSE adpPpr` branch — only our enum can
  reach it). An invariant a vendor's data pipeline upholds gets an active gate and a
  COUNTERFACTUAL fixture (the injury trio: Sleeper currently clears all three fields
  together — verified by one `jq` count, on one day's payload — but that's their
  implementation detail, and a breach arrives silently on the next sync). The fixture
  manufactures data the source doesn't currently produce, because its job is to pin our
  behavior for the day it does.
  - **Interview line:** *"I split defensive coding by who owns the invariant. An
    invariant my own types enforce gets documented — nothing outside the repo can breach
    it. An invariant a vendor's pipeline upholds gets an active gate and a
    counterfactual fixture, because I verified it exactly once, on one day's payload."*

- **Hibernate 6 maps every attribute along two axes — a JavaType and a JdbcType — and
  `ddl-auto=validate` checks the JDBC axis.** A plain `Integer` expects `integer`, meets
  the DDL's `SMALLINT`, and fails startup. `@JdbcTypeCode(SqlTypes.SMALLINT)` overrides
  only the JDBC axis: the schema is the contract, the ORM adapts — not the other way.
  - **Interview line:** *"Hibernate 6 maps attributes along two axes — Java type and
    JDBC type — and schema validation checks the JDBC axis. When my column is SMALLINT
    and my field is Integer, I override the JDBC axis with @JdbcTypeCode rather than
    widening the column: the schema is the contract."*

- **Docker: published ports are for host traffic; `docker exec` runs inside the
  container's network namespace.** `psql` wasn't on the Windows host, but the Postgres
  image ships its own client — `docker compose exec postgres psql ...` on the container's
  native 5432; the 5433 mapping only exists for connections crossing the boundary.
  - **Interview line:** *"Published ports translate traffic entering from the host;
    docker exec puts you behind the translation, so the service is just on its native
    port."*

- **Coverage-profile the data BEFORE judging the agent on it.** The pre-acceptance
  queries (95% of the draftable board has role data, 5% genuine free agents, 1-in-7
  carrying injury status) turn a vague transcript into an explainable one — a weak
  answer about a specific player is checkable against known coverage instead of becoming
  a model-quality mystery.

### Phase 4.3 — Mistakes & lessons

- **The F4 fix as first delivered treated the symptom field.** The delegate fixed
  `espn_id` and stopped; the review asked "what ELSE does this mechanism touch," found
  `created_at`, and a production query confirmed 3,217 live nulls. Mechanism-scope
  review is now a standing habit: every quiet-overwrite fix must enumerate the full
  set of columns the write path doesn't source.
- **`RestClient.create()` vs the injected builder — the premise correction cut both
  ways.** I flagged the bare client as convention drift; the delegate checked and found
  `EspnClient` does the same, so there WAS no drift — it applied the better idiom to the
  uncommitted code anyway and left the committed client alone. A review conditional is a
  hypothesis; the delegate verifying it instead of inheriting it is the same discipline
  we demand in the other direction.
- **Class-wide `@MockitoSettings(LENIENT)` had no justification** — strict stubbing
  passed with zero dead stubs once challenged. Lenient-by-default disables the exact
  tool (dead-stub detection) that catches fixture rot later; it must earn its presence
  with a concrete strict-mode failure, documented.

### Phase 4.3.1 — Tool-Surface Graduation (findPlayer + getTeamContext) ✅ COMPLETE

The 4.3 acceptance run WROTE the 4.3.1 requirements — the starve-then-retrieve method
operating at steady state. Two tools graduated from the deferred list on transcript
evidence, three commits (E, F, H), 310 tests green at close. Findings ledger F1–F9 all
closed with evidence.

### Phase 4.3.1 — What I built (concrete)

- **`findPlayer` (F7 — Commit E):** name→playerId resolution. Partial, case-insensitive,
  active-only, capped at 5 candidates; each carries playerId, position, team (NO_TEAM
  label for free agents), `drafted`/`takenAtPick` against the BOUND session, and
  `hasProjection`. Batch lookups (one drafted query, one ids-only projection-existence
  query), empty-result short-circuit, blank name throws (error-tool-response path). The
  board description gained the truncation warning.
- **`getTeamContext` (Commit F):** one team's bye + early opponents + the depth-chart
  ROOM at a normalized position. **`POSITION_LADDERS`** — the 4.3 vocabulary graduation
  trigger firing, minimally: a one-way read-boundary map (`WR → {LWR, RWR, SWR}`); the
  landing column stays raw, no enum. Room entries carry playerId (so the handcuff's
  profile needs no second name-resolution hop), raw ladder + order, the F1-gated injury
  label (single home: `ProfileScoringService.injuryLabel`, extracted not duplicated),
  and session drafted flags — computed at the TOOL layer, because pointing the
  reference-data `team` package at the draft domain would invert the dependency arrow.
  Unknown team degrades loudly IN THE RESULT (a note naming the bad input); unknown
  position throws. Case-normalized inputs ("sf"/"rb" → SF/RB).
- **F8 (description legend):** "LWR/RWR are outside receivers, SWR is the slot receiver"
  — one sentence that changed the model's reading of the same raw data.
- **Commit H (F9 — pair-gated degradation):** `depth_chart_position` is the
  authoritative field of the role pair; position null → order suppressed alongside it.
  The regression fixture is the REAL arbitration row verbatim (Aiyuk: position null,
  order 3, status DNR — production data, 2026-07-08). Plus `@JsonInclude(NON_NULL)` on
  the nested `RoomEntry` (class-level annotations don't cascade to nested records).
- **Schema-safety test widened to five tools** — each exposing EXACTLY its documented
  parameters, sessionId/rules still unreachable.
- **Acceptance:** all four §7 prompts passed live, each a before/after pair against the
  4.3 transcripts that demanded the tool.

### Phase 4.3.1 — What I learned

- **The agent fabricated a fact from a TRUNCATED VIEW — and the fix was capability +
  description, not prompt scolding (F7).** Asked about a player below the board's top-N
  VORP slice, the agent asserted "he's already been taken." He wasn't (ADP 185.6, WR72 —
  exactly where the diagnosis predicted). The model behaved rationally given its tools:
  nothing said the view was truncated, and no tool resolved a name, so it invented the
  most plausible explanation for an absence. After: the same prompt produced a grounded
  scouting report and correct round-band advice.
  - **Interview line:** *"My acceptance run caught the agent asserting a player was
    drafted when he'd simply fallen below the board tool's top-20 slice. It wasn't
    hallucinating stats — it was making an unsound inference from a truncated view,
    because no description said the view was truncated and no tool could resolve a
    name. The fix is capability and description, not prompt scolding."*

- **The graduation rule fired three times in one increment, each on transcript
  evidence:** `findPlayer` (the fabrication), `getTeamContext` (asked a team-room
  question, the agent said VERBATIM "my tools only surface depth chart details through
  individual player profiles … I don't have a way to pull his handcuff's profile without
  knowing that player's ID" — the requirements written by the capability gap it names),
  and `POSITION_LADDERS` (the first Java branch on the raw vocabulary — the exact
  trigger recorded in 4.3's design). And it correctly REFUSED once: I nearly built
  `get_team_context` early on enthusiasm; the rule demanded the hearing first, and the
  hearing produced better requirements (playerId on room entries came straight from the
  failure quote).
  - **Interview line:** *"I gate every new tool on transcript evidence, and the fourth
    tool's requirements arrived in the agent's own words — asked a team-room question,
    it named the exact capability it lacked. The failure transcript is the requirements
    document; when I was tempted to build the tool early, running the hearing first
    produced a better field list than my spec had."*

- **Undefined vocabulary outsources interpretation to the model's prior (F8).** The raw
  `SWR` label passed through the pipeline perfectly, and the model expanded it into
  "split wide receiver" — wrong, plausible, and invisible unless you know the domain.
  Correct data and a misinformed user, simultaneously. One legend sentence in the
  description fixed the reading; a controlled before/after (same player, same facts)
  proved it.
  - **Interview line:** *"My pipeline served a raw acronym flawlessly and the model
    invented the wrong expansion for it. Correct data, misinformed user. Raw vocabulary
    is fine to serve — undefined vocabulary is not; the legend lives in the tool
    description, not in a normalization layer nothing else needs."*

- **Degradation must be ATOMIC over fields that are only meaningful together (F9).**
  The source delivered a rung without a ladder (order 3, position null); the view
  degraded the fields independently, so the model was handed "role unconfirmed" AND an
  orphaned number — and faithfully composed them into a contradiction ("role
  unconfirmed, and third on the depth chart"). The audit showed the contradiction was
  OURS, not the model's. Pair-gate: the authoritative field of the pair governs both
  (the F1 injury rule's shape, reapplied). The reverse shape (ladder without rung) stays
  ungated — partial but not contradictory. Post-fix, the model composed the two honest
  degradations it could see into an EXPLANATION instead ("role unconfirmed, a direct
  reflection of that injury uncertainty").
  - **Interview line:** *"My agent said 'role unconfirmed' and 'third on the depth
    chart' in one answer — and the audit showed the contradiction was mine: the source
    sent a rung without a ladder and my view degraded the fields independently.
    Independent facts degrade independently; paired facts degrade atomically. The
    regression fixture is the production row that exposed it, verbatim."*

- **The model's capability surface is EXACTLY the `@Tool` methods on the object handed
  to the loop.** When I wasn't sure whether `get_team_context` existed (an internal
  service shared its name), the check wasn't the service layer — it was `grep @Tool` and
  the emitted schema the safety test parses, because that's the only contract the model
  sees. An internal `@Service` is invisible to it.
  - **Interview line:** *"When I wasn't sure whether a tool existed, I didn't check the
    service layer — I checked the emitted tool schema, because the model's capability
    surface is exactly the annotated methods on the object I hand the loop. Everything
    else in the codebase doesn't exist for it."*

- **Grounding includes refuting the user.** An acceptance prompt smuggled in a false
  premise ("even if they have the same bye" — they didn't: weeks 6 and 7, both
  cross-checked against the sync report), and the enriched agent corrected it from
  retrieved schedule data instead of accommodating it. Strictly harder than answering a
  well-posed question, and only possible because the facts sit in tool results rather
  than the model's imagination.
  - **Interview line:** *"My acceptance prompt contained a false premise and the
    enriched agent refuted it from retrieved data instead of accommodating it. Grounding
    isn't just answering correctly — it's the ability to tell the user their question is
    wrong."*

- **The degradation path was UNREACHABLE through the agent until findPlayer existed** —
  playerIds only came from the board slice, and the players whose degradation mattered
  (free agents) rarely sit in a top-VORP slice. Code pinned by unit tests can still be
  dead through the capability surface; 4.3's assertion (c) formally moved into 4.3.1's
  acceptance, where the Cooks prompt closed it: every missing fact reported as
  explicitly unavailable, and the knowns (a two-year production decline, a near-zero
  projection) still composed into the right recommendation.
  - **Interview line:** *"The strongest acceptance transcript wasn't a data-rich player
    — it was a free agent with almost none. The agent found him by name, reported every
    missing fact as unavailable, and still composed the knowns into the right call.
    Grounding was proven on the degradation path, where fabrication is most tempting."*

- **Git's index is CONTENT-granular, not file-granular.** H's hunks shared a file with
  F's; a plain `git add` would have collapsed the commit boundary the plan protected.
  `git add -p` stages individual hunks (`s` to split, `e` to hand-edit — and hand-editing
  is where the separation rule stops paying); `git diff --cached` reviews the staged
  snapshot before each commit — the review-the-diff discipline applied to my own index.
  Also: double-letter `git status` (`MM`/`AM`) is two diffs — index vs HEAD, working
  tree vs index — and committing takes the snapshot, not the file.
  - **Interview line:** *"Git's index is content-granular — git add -p stages individual
    hunks, so one file's changes can split cleanly across two commits. I verify each
    staged snapshot with git diff --cached before committing, because the index is a
    draft I review, not a formality I pass through."*

- **Permitted model arithmetic gets verified anyway.** "28 points clear of Jefferson"
  is a subtraction the model performed over engine numbers — allowed by the boundary,
  and checked regardless: 89.84 − 61.64 = 28.20 ✓, and the implied claim (Jefferson is
  WR2 by VORP) matched the board ordering. Model math over engine numbers stays on the
  watch list, not the trust list.

### Phase 4.3.1 — Mistakes & lessons

- **Acceptance ran ahead of review.** The Cooks transcript arrived before E/F's diff and
  closure report did — acceptance passing proves the happy path, not the safety
  properties (schema widened, parameters closed, lookups batched). Both evidence types
  are required, in order; the review caught nothing this time, but "it happened to be
  fine" is not the process.
- **The staging plan contained a self-contradiction the delegate diagnosed and then
  prescribed** — it named the shared-file hunk problem and then said "git add + commit."
  Closure reports get read for internal consistency, not just completeness.
- **My spec's field list was wrong twice, and the delegate's deviations were right
  twice** — playerId on room entries (justified by the verbatim failure quote) and
  drafted-flags at the tool layer (package dependency direction). The spec-is-intent
  lesson from 4.2, now with the pattern: the best deviations cite either the transcript
  or an architectural rule.

### Phase 4.3 / 4.3.1 — To revisit / deferred

- **Staleness guard:** the agent asserted a player's availability from conversation
  memory (iterations: 0) rather than a fresh state call — fine in a solo session, a
  risk in a live draft where picks land between turns. System-prompt nudge if it bites;
  not code.
- **`created_at` repair:** one-off `UPDATE player SET created_at = updated_at WHERE
  created_at IS NULL` (approximate — stamps last-sync time). Run once post-Commit-D if
  not already done; verify with the F6 count query (expect 0 nulls).
- **HTTP client standardization (widened):** SleeperClient → RestClient; SleeperClient +
  EspnClient → injected `RestClient.Builder` (EspnScheduleClient already does). One
  standardization commit, never mixed with a feature.
- **In-season "next opponents from current week":** earlyOpponents is deliberately fixed
  at weeks 1–3 (pre-draft, "next" = "first"); Phase 5 introduces a current-week concept.
- **8 draftable players without espn_id:** DynastyProcess crosswalk gaps, not the F4
  bug; harmless to 4.3 (schedule keys on team abbrevs). Revisit only if an ESPN-keyed
  read path appears.
- **The 4-in-3,221 note:** the four players added between syncs carry only blob-sourced
  fields until the next id-mapping run — the standing sync-all ordering handles it.

### Phase 4.3 / 4.3.1 — Concepts Cheat-Sheet (additions)

**Hibernate write-path mechanics**
- Flush runs a FIXED action order (inserts before entity deletes) regardless of call
  order — delete-and-reload on identical PKs needs a bulk JPQL `@Modifying` delete
  (executes immediately, bypasses the persistence context) with
  `flushAutomatically = true` (push pending work first) and `clearAutomatically = true`
  (detach stale managed rows after)
- Derived `deleteBy` is select-then-remove → same-id old/new instances collide as
  `NonUniqueObjectException`
- Merge copies the detached entity's ENTIRE state, null included — carry every
  non-source-owned column forward explicitly; `Persistable.isNew()` picks the path,
  never the payload
- `@JdbcTypeCode(SqlTypes.SMALLINT)` on `Integer` — Hibernate 6's two-axis type model
  (JavaType/JdbcType); `ddl-auto=validate` checks the JDBC axis; bend the ORM to the
  schema

**Retrieval-layer design rules (the 4.3 set)**
- Profile the source's distinct values before designing the schema; land raw vocabulary;
  normalize only for a Java reader (the graduation trigger), and then minimally (a
  one-way read-boundary map, not an enum on the landing column)
- Write boundary throws on vocabulary it can't crosswalk; read boundary LEFT-JOINs and
  degrades to distinct loud strings — one home for the vocabulary so views can't drift
- Derived facts (bye) rebuild on sync with a proof-shaped guard (17 rows + one gap) —
  absent beats wrong
- Independent facts degrade independently; PAIRED facts degrade atomically (the
  authoritative field governs the pair — injury_status over its trio,
  depth_chart_position over its order)
- Coverage-profile the data before judging the agent on it

**Agent tool-surface rules (the 4.3.1 set)**
- A truncated view without a truncation warning invites unsound inference — say so in
  the description AND provide the resolving capability (name → id)
- Undefined raw vocabulary outsources interpretation to the model's prior — legends live
  in descriptions
- The capability surface is exactly the `@Tool` methods on the loop's object — verify
  with the emitted schema, not the service layer
- Graduation rule at steady state: the acceptance run writes the next increment's
  requirements; the best tool specs quote the failure transcript
- Session-relative facts (drafted flags) attach at the tool layer, not inside
  reference-data services — package dependency arrows outrank field convenience
- Class-level `@JsonInclude` does NOT cascade to nested records

**Git (the commit-boundary set)**
- The index is content-granular: `git add -p` stages hunks (`s` split, `e` hand-edit);
  `git diff --cached` reviews the staged snapshot before every commit
- Double-letter status (`MM`) = two diffs (index↔HEAD, tree↔index); commit takes the
  snapshot, not the file
- Testcontainers' "could not find a valid Docker environment" = Docker isn't up yet,
  not a test failure

---

## Phase 4.M — The Migration: Java 21 + Spring Boot 4 + Spring AI 2.0 (+ 4.M.1)

### What I did

- **Made the sequencing decision from verified facts, not reflex:** web-checked the
  version landscape before deciding — Spring AI 2.0.0 GA shipped June 12 2026; Spring
  Boot 3.5 / Framework 6.2 hit EOL June 30 2026 (the project was on an unpatched
  baseline); 2.0 reshaped exactly the embedding/VectorStore/pgvector APIs Phase 4.4
  would be written against. Verdict: migrate BEFORE 4.4, as a named increment (4.M),
  or write 4.4 twice.
- **Commit 1 — Java 17 → 21 in isolation**, on Boot 3.5 (a legal intermediate state),
  so platform and framework never shared a suspect list. 310 green before touching the
  framework. Fixed the JDK-21 dynamic-agent warning by registering Mockito as an
  explicit `-javaagent` via Surefire + `dependency:properties` (no hardcoded paths).
- **Captured behavioral baselines BEFORE migrating:** raw curls of four endpoints
  (state, board, profile, a 404 ProblemDetail), deterministic psql dumps of two
  `source_payload` rows (rotowire 10210, espn 10213), and TWO runs of the same agent
  prompt with full DEBUG loop logs — two runs to measure the LLM's natural variance
  and set equivalence bands empirically (tokens 10,955 and 13,308 on identical stack).
- **Wrote the 4.M spec in chat, executed in Claude Code** (commit 8c24c97): parent
  3.5.14 → 4.0.7, Spring AI 1.1.8 → 2.0.0, Jackson 3 sweep, Boot-4 modularization
  fallout (starter-webmvc, starter-restclient, starter-flyway, split test-slice
  starters, Testcontainers BOM 2.0.5), three Spring AI property renames caught by
  `spring-boot-properties-migrator` (added, used, removed before commit). Findings
  ledger V-1..V-4 + F-1..F-11, evidence per item. Hibernate 7 rode along with ZERO
  persistence changes — the integration suite pinned all five exposure points.
- **Ran the acceptance runbook and caught what 310 green tests couldn't:** the agent
  was silently broken on the wire. Diagnosed from token forensics, fixed with full
  provider-typed options (commit 13cff9b), verified at the HTTP body with
  `ANTHROPIC_LOG=debug`, and pinned with a new schema/options test — 311 green.
- **4.M.1 — the confabulation fix:** the post-fix acceptance runs surfaced a
  memory-narrative failure (the model invented events between turns); landed a
  "State and memory discipline" section in `draft-agent-system.txt` as its own
  increment, verified by paired before/after transcripts per the 4.3 method.

### What I learned

- **Framework couplings make upgrades atomic by construction.** Spring AI 1.1.x is
  compiled against Framework 6/Jackson 2; 2.0 against Framework 7/Jackson 3 — there is
  NO legal state where Boot and Spring AI move separately. A one-line parent bump
  transitively forces Jackson 3, Framework 7, Hibernate 7, and a reshaped tool API
  through the codebase in one commit window. That's what makes it a specced increment,
  not a dependency bump.
  - **Interview line:** *"I couldn't stage Spring Boot 4 and Spring AI 2.0 separately —
    each Spring AI line is compiled against one framework generation, so the upgrade is
    atomic by construction. I isolated what COULD move alone (the JDK, one commit
    earlier) so that platform and framework changes never shared a suspect list."*

- **Java 17 → 21 is cheap because it's additive; the risk lives in bytecode-touching
  libraries** (Mockito/ByteBuddy, Hibernate proxies, Lombok) needing the new class-file
  version — not in application code. The one visible wart: JDK 21 warns on dynamically
  attached agents (Mockito self-attaches); the clean fix is an explicit `-javaagent`
  in Surefire, because dynamic attach is slated to become an error.
  - **Interview line:** *"17 to 21 is low-risk because it's additive — the
    compatibility risk is bytecode-manipulating libraries supporting the new class-file
    version. I bumped the JDK as an isolated commit before the framework migration and
    verified against byte-identical regression anchors."*

- **Two verification regimes, split by determinism class — decided BEFORE migrating.**
  Deterministic surfaces (endpoints, DB payloads, scoring anchors) get byte-identical
  diffs against captured baselines. The probabilistic surface (the agent) gets
  equivalence criteria — same tool SET, iteration cap, token band — derived from TWO
  pre-migration runs, because without a second run there are no error bars to
  distinguish sampling noise from regression.
  - **Interview line:** *"How do you regression-test a system with an LLM in it?
    Partition by determinism class. Deterministic code gets byte-identical anchors;
    the LLM path gets equivalence bands measured from repeated baseline runs before
    the migration — so you can't grade on a curve afterward."*

- **THE incident: 2.0's Anthropic module silently replaces foreign options with a
  blank.** The module was rewritten on the official `anthropic-java` SDK and no longer
  merges prompt-level options with configured defaults — and one level deeper than the
  docs state it: `options instanceof AnthropicChatOptions ? options :
  AnthropicChatOptions.builder().build()`. My generic `ToolCallingChatOptions` lost
  the tool schemas, the model override, and max-tokens in one stroke. The model,
  told about tools in its system prompt but given none in the request, ROLEPLAYED the
  tool calls as `<function_calls>` text — with invented snake_case names, because it
  never saw the real schema. Every component behaved correctly given its inputs; the
  failure was request marshalling.
  - The diagnostic was **token forensics**: iter-0 input dropped 2,077 → 479, and the
    ~1,600-token delta matched the size of five tool schemas — proving the loss point
    was marshalling, not the loop and not the model.
  - The fix: prompt-level options must be FULL provider-typed or null —
    `((AnthropicChatOptions) chatModel.getOptions()).mutate().model(...)
    .toolCallbacks(...).build()`, so property-configured defaults
    (max-tokens 2000, temperature) survive. ChatClient callers (VerdictClassifier,
    LeagueParsingService) were unaffected — ChatClient performs its own merge, which
    is exactly why `options(...)` takes a builder in 2.0.
  - **Interview line:** *"After a major Spring AI migration, all my tests were green
    and the agent was silently broken — the SDK-backed Anthropic module replaces a
    non-provider options object with a blank one, so my tool schemas never reached the
    API and the model roleplayed the calls as text. Mocked tests structurally cannot
    catch request-marshalling regressions; my pre-captured behavioral baseline caught
    it in one curl — input tokens dropped by almost exactly the size of the missing
    schemas."*

- **Loud-failure asymmetry, validated at framework scale — from the victim's side.**
  The module had three choices for a foreign options type: merge, THROW, or substitute
  a blank. It chose the one that degrades toward plausible: a blank options object
  still yields a valid API call, so nothing failed until behavior was compared against
  a baseline. A throw would have been a thirty-second stack trace.
  - **Interview line:** *"The framework degraded toward plausible instead of noisy — a
    blank options object still produces a valid API request. That incident is why I
    design my own seams to throw on unrecognized input rather than substitute
    defaults."*

- **Mocks cover YOUR side of the seam; the wire needs its own eyes.** The new
  `promptOptionsAreFullAnthropicOptions` test pins that the Prompt carries full
  provider-typed options with all five callbacks — the closest a mocked test can get.
  Beyond that point, verification is the HTTP body itself: the official SDK honors
  `ANTHROPIC_LOG=debug`, and the acceptance run confirmed `"tools"` with five schemas
  and `"max_tokens": 2000` outbound. Pinning test + behavioral baseline is the
  complete pair.

- **Jackson 3 in practice:** `com.fasterxml.jackson.{core,databind}` →
  `tools.jackson.*` but ANNOTATIONS keep the old package (don't "fix" them);
  `JsonProcessingException` (checked, extends IOException) → `JacksonException`
  (unchecked) — catch seams stop being compiler-enforced, so parse-failure paths get
  re-verified by test; mappers are immutable — feature flags move to
  `JsonMapper.builder()`; bean properties now sort alphabetically by default while
  creator-bound properties (records) keep declaration order — which is why my views
  were byte-identical and `ProblemDetail` (getter-based) reordered its keys. Accepted:
  JSON object member order carries no meaning, and any client parsing by position is
  already broken.
  - **Interview line:** *"Jackson 3 made its exceptions unchecked — the ecosystem-wide
    retreat from checked exceptions in library APIs. The caller usually can't recover
    from a parse failure at the catch site anyway; the migration cost is that your
    catch seams stop being compiler-enforced, so I re-verified every parse-failure
    path by test, not by compiler."*

- **The migration removed a latent concurrency wart:** a parse error used to ride the
  combined `IOException|InterruptedException` catch and SET THE INTERRUPT FLAG on a
  bad payload. Jackson 3's unchecked exception forced its own catch; the
  `InterruptedException` path still restores the flag (load-bearing — catching it
  clears the status, and swallowing deafens everything above).

- **Baselines are raw bytes.** The board baseline's `6.9` vs the runtime's `6.90` was
  settled by DEDUCTION, no time machine needed: the new output prints `6.90`, which a
  double cannot produce, so the field is BigDecimal; valuation/ was untouched, so it
  was BigDecimal before; Jackson 2 preserves BigDecimal scale (my own profile baseline
  proved it with `416.40`); therefore the old runtime ALSO emitted `6.90` and the
  baseline file was zero-stripped by a pretty-printer at capture (Boot emits compact
  JSON — every pretty baseline file passed through SOME formatter; jq-style tools
  reparse numbers into doubles and re-emit canonically).
  - **Interview line:** *"A migration diff disagreed with my baseline file. Instead of
    trusting either memory, I proved from the artifacts themselves that the old
    runtime couldn't have emitted the baseline's value — the capture had gone through
    a number-canonicalizing formatter. Standing rule since: baselines are raw bytes;
    formatting is for reading, not storing."*

- **Verify against the jars, not the doc, not the chat.** My proposed fix used
  `getDefaultOptions()` — deprecated forRemoval in 2.0; the executor corrected to
  `getOptions()` against the actual jar, the same `javap` discipline that verified
  `internalToolExecutionEnabled` in 4.2. Neither the migration doc nor either side of
  the chat is the source of truth.

- **The migration silently edited part of my prompt surface.** 2.0 regenerates tool
  JSON schemas (OpenAPI format hints, required-handling) — iter-0 input shrank ~150
  tokens on an identical prompt. Tool descriptions are the primary behavioral lever
  (the 4.3 lesson), so a schema rewrite is a prompt change: the post-fix runs shifted
  the recommendation from the highest-VORP WR toward the TE-cliff argument — both
  fully grounded, every number verbatim from the board, a judgment flip near a real
  decision boundary. n=2 vs n=2 is too small to call it systematic; parked for a
  distribution tally, not treated as a defect. The invariant that matters held in
  every run: the model never originated a number.
  - **Interview line:** *"Behavioral acceptance after a migration has to distinguish
    BROKEN from SHIFTED. My grounding invariant held — every cited number still traced
    to tool results — while the judgment shifted, because the framework regenerated my
    tool schemas and a schema rewrite is a prompt change."*

- **The confabulation (4.M.1's trigger):** on a repeated question, the model inferred
  a WORLD event from a CONVERSATIONAL event — "you already took McBride based on my
  prior advice" — and narrated the false premise in the same paragraph as the fresh
  tool data contradicting it (`iterations: 1` — it HAD current state in hand). Worse
  than staleness: false narrative DESPITE fetching. This was the 4.3.1 deferred
  staleness item biting in mutated form; the graduation rule fired on the transcript.
  - The fix is prompt-level epistemics, one increment, one file: memory holds prior
    ADVICE, not events; a repeated question means only that the user asked again;
    roster/picks/availability come exclusively from CURRENT-turn tool results; memory
    alone may answer questions about the conversation itself (preserving the 4.2
    zero-tool recall behavior); and reconciliation between expectation and evidence
    happens SILENTLY — the model may think what it wants about why you asked twice,
    it may not ship the speculation.
  - **Interview line:** *"My agent confabulated on repeated questions — it inferred
    from repetition that real-world events had happened, then narrated the false
    assumption even though fresh tool data in the same turn contradicted it. The fix
    was prompt-level epistemics: memory holds prior advice, not events; state
    questions require current-turn tool results; reconciliation happens silently. I
    verified it the way I found it — paired before/after transcripts."*

- **Calibrate the criteria, not just the system.** Run new2 also fell below the token
  band's floor — but the band was measured on FRESH-research turns, and a
  memory-informed turn that skips re-profiling is behavior 4.2 celebrated. The
  criterion was wrong, not the agent: fresh-turn band applies to fresh turns; memory
  turns are judged on grounding alone. (And the 4.M.1 prompt section adds ~200 tokens
  to iter-0 input — the band moves WITH the prompt; note new baselines instead of
  reading growth as drift.)

### Phase 4.M — Mistakes & lessons

- **The ledger claimed the maxTokens default jump (500→4096) was "neutralized by our
  explicit 2000" — false at the agent call site.** The 2000 lives in configured
  DEFAULTS, and 2.0 ignores defaults whenever prompt-level options are present. A
  self-consistent claim that was wrong about the mechanism — caught only because the
  options incident forced the mechanism into the open. Review the diff, not the
  self-report, includes reviewing the self-report's REASONING.
- **Three of four baseline files were formatter-processed** (mixed indentation across
  the set was the tell; one formatter stripped number lexemes). The methodology
  survived because ENOUGH artifacts were trustworthy to deduce the truth — but
  capture-time hygiene would have made deduction unnecessary.
- **My fix proposal was written from the migration doc and used a
  deprecated-for-removal method.** The doc lags the jar even in the release it
  documents.
- **The executor's first fix commit nearly swept my untracked capture files in**
  because they sat staged; it caught itself and redid the commit. Standing rule out
  of it, now in CLAUDE.md: the executor commits ONLY files its spec names — explicit
  paths, never `git add .`, never whatever's staged.
- **The failing artifacts were nearly overwritten by the re-run.** The 479-token
  roleplay transcript is F-12's before-picture — preserved under the baseline folder
  as evidence, because "input tokens dropped by the size of the schemas" is a story
  worth SHOWING, not just telling.

### Phase 4.M — To revisit / deferred

- **SleeperClient catch-split:** the combined `IOException|InterruptedException` catch
  still sets the interrupt flag on plain network errors (wrong — nothing interrupted
  the thread). Three-way split (JacksonException / IOException / InterruptedException)
  folds into the already-deferred RestClient standardization, where the seam gets
  redesigned anyway.
- **Testcontainers BOM pin (2.0.5):** Boot 4 no longer manages TC versions — the pin
  must be bumped ALONGSIDE future Boot parent bumps, deliberately.
- **ObjectMapper-supertype injection sites:** work fine (Boot 4's auto-configured
  JsonMapper satisfies them); ambiguous only if an XmlMapper bean ever appears.
  Standardization, own commit.
- **Recommendation-distribution tally:** ASB vs McBride at this board state, five-run
  sample on the new stack, if the flip starts mattering to trust.
- **ToolCallingAdvisor vs the manual loop:** 2.0 canonized the manual pattern
  (user-controlled DefaultToolCallingManager loop, documented for exactly our
  reason — full control); re-evaluating advisor-based execution + tool interception
  is a design session, not a migration task.
- **New fresh-turn token baseline** post-4.M.1 prompt growth: capture on the next
  acceptance pass.

### Phase 4.M — Concepts Cheat-Sheet (additions)

**Migration methodology (the 4.M set)**
- Decouple what CAN move alone (JDK) from what can't (Boot+Spring AI) — one variable
  per commit, one suspect per regression
- Capture baselines on the OLD stack, raw bytes, deterministic queries, TWO runs of
  anything probabilistic (variance = error bars)
- Byte-identical anchors for deterministic code; equivalence bands for LLM behavior —
  criteria written BEFORE the migration
- The upgrade notes outrank the spec; the jars outrank the upgrade notes
- Temporary `spring-boot-properties-migrator`: add, fix, verify zero warnings, REMOVE
  before commit

**Spring AI 2.0 (the breaking set)**
- ChatModels never execute tools; `internalToolExecutionEnabled` gone — external
  execution is the only mode; the manual ChatModel+ToolCallingManager loop is now the
  documented full-control pattern
- The Anthropic module is a thin adapter over the official SDK: prompt-level options
  are used AS-IS — full provider-typed or null; foreign options types are silently
  replaced with a blank (`instanceof` check), NOT merged, NOT rejected
- ChatClient merges options itself (`getOptions().mutate()` + combine) — ChatClient
  callers are insulated; bare ChatModel callers are not
- `getDefaultOptions()` deprecated forRemoval → `getOptions()`
- Anthropic maxTokens default 500 → 4096; configured defaults apply ONLY when the
  prompt carries no options
- Tool JSON schemas regenerated (OpenAPI hints) — a schema rewrite is a prompt change;
  expect behavioral shift, judge against the grounding invariant
- Wire-level eyes: `ANTHROPIC_LOG=debug` on the official SDK logs outbound bodies

**Jackson 3 (the breaking set)**
- `com.fasterxml.jackson.{core,databind}` → `tools.jackson.*`; annotations KEEP the
  old package
- Checked `JsonProcessingException` → unchecked `JacksonException` (no longer an
  IOException) — catch seams leave the compiler's protection
- Mappers immutable: `JsonMapper.builder().disable(...).build()`
- Bean properties sort alphabetically by default; creator/record properties keep
  declaration order; `FAIL_ON_UNKNOWN_PROPERTIES` off by default (keep it explicit if
  the leniency is intent)
- jsonb in Postgres normalizes key order on write — property-order changes are
  invisible there; value SHAPES are what to diff

**Boot 4 modularization (the pom set)**
- starter-web → starter-webmvc; RestClient.Builder auto-config moved to
  starter-restclient; Flyway auto-config moved to starter-flyway; test slices split
  into starter-webmvc-test / starter-data-jpa-test (annotation packages moved, slice
  behavior identical)
- Testcontainers version management dropped — own BOM import, renamed artifacts
  (testcontainers-junit-jupiter, testcontainers-postgresql)
- Hibernate 7 / Jakarta Persistence 3.2 ride along — integration tests on the real
  container are the detector; change nothing preemptively

---

## Phase 4.4 — Vector News RAG (+ 4.4.1)

### What I did

- Ran a seven-candidate news-source audit against a written rubric (player-ID
  linkability, structure, category coverage, cadence, backfill, licensing, dedup
  signal) — raw curl probes captured as bytes BEFORE any design. Selected the
  ESPN player-news endpoint, `type == "Rotowire"` items only.
- Ratified three design decisions in chat, each on evidence: **D1** landing/derived
  split (`player_news` insert-only landing + `news_embedding` rebuildable mart),
  **D2** OpenAI `text-embedding-3-small` @ 1536 dims as a retryable batch step
  downstream of ingestion (deterministic fake `EmbeddingModel` at the test seam),
  **D3** `PgVectorStore` adopted with `initializeSchema=false`, Flyway-owned DDL,
  startup schema validation ON, dimensions pinned, deterministic document UUIDs.
- Audited `PgVectorStore` and `PgVectorSchemaValidator` by parsing the class-file
  constant pools out of the 2.0.0 jar: extracted the DDL template, the upsert and
  cosine-search SQL, the builder surface, and proved the validator checks column
  NAMES + vector dimensions (`pg_attribute.atttypmod`) but never column types —
  which cleared `jsonb` metadata against the store's `json` template.
- Executed via Claude Code against a four-commit spec (A crosswalk repair +
  degradation vocabulary, B V15 + ingestion, C V16 + store + embed build, D the
  sixth tool `searchPlayerNews`); reviewed by diff with an R-1..R-7 findings
  ledger; seeded ~30k blurbs (2018→now) and embedded the corpus (~$0.07).
- Shipped 4.4.1 (V17: landing PK widened to the association triple
  `(source, news_id, player_id)`; embedding UUID gains `playerId`) — then had my
  own R-1 diagnosis FALSIFIED by the fix's verification gate, and amended the
  finding in the review doc.
- Closed acceptance per the frozen §9 criteria: 5/5 starvation probes re-run
  (4 pass, 1 fail with a named out-of-scope cause), staleness probe passed, and a
  fresh-turn token baseline captured ×2 — byte-identical (2430/2430; iter-1
  3532/3532).

### The Phase 4.4 stories (interview-bearing)

**The source audit falsified my models twice — then the review falsified me a
third time.** Probe 1 (Mahomes, 7 items, oldest May) fit a rolling-window
retention model; probe 2 (Montgomery, 24 blurbs back to September) killed it;
the replacement blurb-cap model died the same day (33 blurbs on a fringe
player). Final characterization: retention is opaque and INVERSELY related to
coverage volume — stars keep 4–5 blurbs, quiet players keep 10 months. That
inverted the loss-risk intuition (the first two draft rounds are the fragile
cohort) and turned sync cadence into a DERIVED number (arrivals must stay under
the ~4-item worst-case buffer → 2×/week through camp).
- **Interview line:** *"My first probe fit a clean retention model and my second
  probe falsified it. Retention turned out to be per-player and opaque, so I
  stopped modeling the source and designed against the characterization: an
  append-only corpus that can never be re-derived from the feed. One sample
  gives you a model; the second tells you whether it was the data's model or
  yours."*

**The free endpoint was a commercial provider in disguise.** Every player-level
item carried `type: "Rotowire"` — ESPN syndicates Rotowire's blurbs, pre-chunked
at 300–700 chars, keyed by an ESPN id I already stored, with a stable numeric
item id. Three payload facts eliminated the chunking layer, dictated
accumulate-don't-rederive, and promoted a dormant crosswalk gap to blocking.
- **Interview line:** *"The audit's key finding wasn't in any documentation — it
  was in the captured payload: the free endpoint syndicated a commercial
  provider's content, pre-chunked, with stable IDs and a rolling window. That's
  why you probe raw responses before designing schemas."*

**Bytecode as the source of truth (jars > docs, applied to a framework store).**
The store's initializer wanted runtime DDL (plus a `DROP TABLE` flag); the
builder let me refuse it: `initializeSchema=false`, Flyway-owned DDL, and the
store's own validator turned from a risk into a startup drift guard — it diffs
the ACTUAL catalog (`atttypmod`) against configuration and fails loudly with
both numbers.
- **Interview line:** *"I didn't trust the docs on whether the schema validator
  would accept my column type — I read the validator's bytecode and found it
  checks names and dimensions, never types. Then I turned it into a guard: with
  dimensions pinned, every startup diffs my migration's real catalog state
  against configuration."*

**The deterministic UUID is the whole migration story in one function.**
`nameUUIDFromBytes(source:newsId:playerId:modelTag)` makes the embed build an
anti-join (idempotent, resumable), makes re-runs free, and makes a model swap a
coexisting-generations flip — inside a table schema the framework dictates.
- **Interview line:** *"I made document IDs a deterministic hash of the natural
  key plus the embedding model. That turned the store's blind upsert into
  idempotent ingestion and made model migrations a coexisting-generations flip —
  a re-embed and a config change, never a data loss."*

**The 429 that proved the loud-failure principle at the vendor seam.** The
first full-corpus embed (~3.6M tokens vs OpenAI's 1M TPM) failed INSIDE
`PgVectorStore.doAdd`, which embeds its entire argument before inserting
anything — every paid embedding discarded, zero rows landed. Fix: feed the
store in 500-doc chunks with bounded exponential backoff; completed chunks are
durable and the anti-join genuinely resumes. Verified idempotence live:
immediate rerun = `embedded: 0, alreadyCurrent: 29980`, zero vendor cost.
- **Interview line:** *"The framework's add() was all-or-nothing across an
  external billing boundary — a rate-limit at 90% cost me 100%. Chunking moved
  the atomicity boundary to match the payment boundary: each chunk durable, the
  deterministic-id anti-join resumes from the gap, and retry pacing rides the
  provider's rate window."*

**Which model REALLY embedded the corpus — verified from the invoice.** The
config said 3-small, the dimension validator passed, the metadata tag agreed —
but the tag is MY property and ada-002 is also 1536-dim, so every green layer
was compatible with the wrong model underneath (the F-12 shape, one layer up).
Token volume × observed spend ($0.15 ≈ 7.2M tokens × $0.02/1M over two passes)
uniquely fit 3-small; ada-002 would have read ~$0.72.
- **Interview line:** *"I verified which embedding model actually ran from the
  bill: volume times spend uniquely fit one model's price point. The billing
  system is a wire-level witness my own configuration can't fake."*

**R-1: the review caught a real anomaly — and then the runbook gate caught the
reviewer.** The seed reported 180 "duplicates correctly deduped" against an
EMPTY table; arithmetic said duplicates within the run. Two mechanisms fit
(same id across players' feeds vs same id re-issued within one feed); I picked
the scarier one WITHOUT running the discriminating probe, declared association
loss, and specced 4.4.1 with calendar urgency. The fix's own verification gate
("expect ≥180 recovered rows") returned ZERO — falsifying my diagnosis. The
corpus probe then showed ESPN mints DISTINCT ids per delivery, even re-issuing
the same blurb to the same player: the 180 were within-feed re-issues,
collapsed correctly, and no association was ever lost. V17's triple PK stays on
its own merits — the row means "this item appeared in THIS player's feed" — but
the defect narrative was withdrawn and the review doc amended.
- **Interview line:** *"My review caught a genuine anomaly in a success report,
  but two mechanisms fit the arithmetic and I shipped a fix for the scarier one
  without running the one query that discriminated between them. The fix's own
  verification gate came back zero and falsified my diagnosis. That's why
  runbooks carry expected numbers — the gate exists to catch the reviewer too."*

**The spec's file list was wrong and the deviation protocol saved the fix.**
4.4.1's file list omitted `PlayerNewsRepository` — whose dedup query still
answered at ITEM granularity; with it unchanged, the PK fix would have silently
re-dropped the associations it existed to keep (and the naive tests would have
stayed green). The executor couldn't make the spec compile, deviated, FLAGGED
the deviation with its reasoning (including rejecting `findAllById` — Spring
Data degrades to query-per-id on composite keys), and the review commended it.
- **Interview line:** *"A spec's file list is a guess about blast radius made
  from chat-side fragments; the executor's deviate-and-flag protocol is what
  makes the guess safe. The flagged deviation was the fix's real completion —
  the PK change alone would have been silently insufficient one layer up."*

### Acceptance (the §9 verdicts)

- **5/5 probes judged: 4 pass, 1 fail with a named cause.** The Montgomery and
  Likely probes now cite dated news for team-change and role context ("per news
  from January 2026"); the Aiyuk injury-timeline probe was the cleanest exhibit:
  the model took the injury from the structured profile, composed its own query
  ("ACL MCL injury recovery timeline return"), cited "per a November 10, 2025
  report," and said the honest thing about an 8-month-stale corpus — "no clarity
  on when he'll be ready in 2026." The staleness function working on the hardest
  shape.
- **The failing probe (rookie RBs) failed on a MISSING FACT, not a news gap:**
  every "rookie" cited was a 2025 sophomore, because nothing in the data model
  says draft class. Grounding held throughout. This is the pre-4.4 `draft_year`
  gap firing on transcript — promoted from deferred to the next increment by the
  graduation rule.
- **4.M.1 discipline held under temptation:** with draft state sitting in
  memory from two questions earlier, the model re-called `getDraftState` anyway
  — state from current-turn tools, memory for advice only.
- **Fresh-turn token baseline: byte-identical across two fresh sessions**
  (iter-0 in=2430 both; iter-1 in=3532 both — prompt, schemas, AND tool results
  deterministic end to end). The number sits one schema's weight above the
  4.M.1-era baseline: the sixth tool, visible as token mass, pointing the
  healthy direction — the same forensic that diagnosed F-12.

### Phase 4.4 — Mistakes & lessons

- **I diagnosed R-1 without the discriminating probe** — the exact
  probe-before-designing discipline this phase started with, violated by the
  reviewer at the moment it mattered. Cost: one migration (kept on merits), a
  dime of embeddings, a false paragraph in a committed doc, misplaced urgency.
  Both the executor's mechanism claim and mine were unverified interpretations;
  the executor's correctness claim was at least RIGHT.
- **I asserted "one probe is unaccounted for" from a count I never verified** —
  "the five probes" was my own spec's phrase, not an artifact I possessed; I did
  subtraction on a list I'd never seen and assigned work defined by the missing
  item. Resolution came the usual way: against the artifact, not memory.
- **The token baseline was captured wrong twice** (probes run inside one
  session; then run-2 sent to run-1's session) — ChatMemory is session-keyed,
  so iter-0 of anything but a FRESH session measures prompt + accumulated
  conversation, a different and unstable quantity. Fresh session per run,
  always.
- **The executor breached the commit rule twice** (my learning-log edits;
  its own self-authored review doc) — owner reviewed and WAIVED both, which is
  the rule working: a deliberate waiver is not a silent breach. The review doc
  kept its executor-authored header and took owner amendments; a self-report
  living in the repo must be labeled as one.
- **`spring.ai.model.chat` / `spring.ai.model.embedding` pins are MANDATORY
  with two providers on the classpath** — both chat autoconfigs are
  `matchIfMissing=true`, so without the pins the context builds two ChatModel
  beans. Found at EV-1, pinned by a contextLoads assertion.
- **Boot ordering at the seam:** the store bean validates in
  `afterPropertiesSet`, which Boot does NOT auto-order behind Flyway —
  `@DependsOnDatabaseInitialization` required, or the drift guard races the
  migration on cold start.
- **Test-fake subtlety:** PgVector's cosine search drops rows at distance ≥ 1,
  so a signed-component fake embedding can fake a NO_NEWS_FOUND — the fake uses
  strictly positive components. Reading the search SQL's threshold semantics is
  part of writing the fake.

### Phase 4.4 — To revisit / deferred

- **`draft_year` / rookie classification (PROMOTED — next increment):** one
  migration + source question first (Sleeper payload vs DynastyProcess CSV),
  profile view + tool description line. The P1 transcript is the evidence.
- **Near-duplicate retrieval (the Schwartz shape):** ESPN re-issues the same
  blurb under fresh ids → near-identical vectors; a fringe player's topK could
  come back majority-duplicates. Watch item, evidence-pending — no dedup layer
  until a transcript shows degradation (graduation rule).
- **Micrometer uri-tag cardinality WARN:** news client interpolates playerId
  into the URL string → 1,867 distinct `http.client.requests` tags. Fix: URI
  template + `uriVariables`. Hygiene, own commit.
- **FantasyPros API key (email pending):** the only backfill lever for the
  star-player March–April gap; nice-to-have, never a dependency.
- **Story-item chunking:** graduation-gated; entity linking pre-solved
  (`data-player-guid` + href ids in ESPN's own markup).
- **Old-generation embedding cleanup after a model swap:** one manual DELETE by
  metadata tag; not worth code until a swap happens.
- **Sync automation:** manual POST 2×/week through camp is sufficient for one
  draft season; scheduling is a later increment.
- Camp cadence operational note: sync ~2×/week from late July (retention buffer
  ≈ 4 items for stars).

### Phase 4.4 — Concepts Cheat-Sheet (additions)

**RAG mechanics (the 4.4 set)**
- Corpus = the body of text the system retrieves over; ours is append-only
  because the source can't be re-queried for history
- The embedding is a COMPUTATION over a fact, parameterized by a model — it
  lives in a derived table keyed by the model identifier, never on the landing
  row (vector dimension is DDL; a model swap must never touch source-of-truth)
- Metadata filter FIRST, similarity SECOND: with a hard `player_id` filter the
  vector only ranks ~30 candidates — filter design outranks embedding-model
  quality at this shape
- Staleness is first-class metadata: `published` must survive landing →
  document metadata → tool result → the model's sentence ("per a Nov 10, 2025
  report"); otherwise RAG launders stale truth into confident current-tense
- Pre-chunked sources (self-contained 300–700-char blurbs) eliminate the
  chunking layer: one item = one embedding row

**Spring AI 2.0 vector stack**
- `PgVectorStore` builder: table name, `initializeSchema`,
  `vectorTableValidationsEnabled`, dimensions, distance type, index type
  (HNSW default; NONE/IVFFLAT exist), `BatchingStrategy` on the embed call
- `PgVectorStore.doAdd` embeds its ENTIRE argument before inserting anything —
  chunk at the caller if the corpus exceeds the provider's TPM window
- The schema validator checks table existence, column NAMES, and vector
  dimensions via `pg_attribute.atttypmod` — never column types (jsonb-safe)
- Two providers on the classpath need `spring.ai.model.chat` /
  `spring.ai.model.embedding` pins (both chat autoconfigs match-if-missing)
- OpenAI embedding property (2.0): `spring.ai.openai.embedding.model` (the
  `.options.model` form is deprecated); Anthropic has NO embeddings API —
  RAG on this stack always means a second provider or a local model
- `Document.builder().id().text().metadata()` is the 2.0 construction form

**pgvector**
- `vector(N)`: the dimension is part of the column TYPE (stored in typmod);
  the ANN index binds to it — dimensions are schema, written down, not
  discovered from the model at boot
- HNSW vs IVFFLAT one-liner: at small corpus size the index is nearly
  cosmetic (planner may seq-scan); HNSW avoids IVFFLAT's train-before-query
  requirement and degrades gracefully as the corpus grows
- Cosine distance operator `<=>`; the store's search thresholds at
  distance < d — vectors at distance ≥ 1 silently drop (test fakes must keep
  scores inside the window)
