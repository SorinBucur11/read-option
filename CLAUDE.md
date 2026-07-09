# Read Option — Project Memory

AI-powered NFL fantasy football draft assistant. Backend + AI integration is the
product; frontend is deferred until after Phase 4.

- **Repo:** `read-option`  ·  **Base package:** `app.readoption`
- **Owner works architecture/design in chat, hands specs here. You execute against the full repo.**
- **Current phase:** Phase 3 — natural-language league customization. Active spec:
  `docs/specs/phase-3.md`. Phases 0–2 complete (ETL, scoring/ranking engine,
  multi-source reconciliation + first RAG increment).

## The architecture principle (do not violate)

**Data providers predict → the Java engine scores, ranks, reconciles → the LLM
classifies/strategizes.** All deterministic math (scoring, ranks, dispersion,
consensus medians, future VORP) lives in the engine. The LLM only reasons about
judgment calls (source disagreement; later, tier cliffs and value-vs-market). The
LLM **never emits a stat or a fantasy-point number**. Every projected number must
trace to a real source or a median of real sources — reproducible and auditable.
When in doubt, the number is the engine's job.

## Stack

Java 21 · Spring Boot 4.0.x · Spring AI 2.0.x (Anthropic, official-SDK-backed) ·
Jackson 3 (`tools.jackson`; annotations stay `com.fasterxml.jackson.annotation`) ·
PostgreSQL 16 + pgvector (Docker, host port **5433** — WSL occupies 5432) · Spring Data
JPA · Flyway · Lombok · commons-csv · Testcontainers 2.x · Maven Wrapper.
Model: `claude-sonnet-4-6`.

## Conventions you MUST hold (cross-cutting — the repo is the source of truth)

- **Entities:** Persistable upsert pattern; `@Builder` with `@Builder.Default` on
  defaulted fields; `@JsonIgnore` on `isNew()`/`getId()`; `@PrePersist`/`@PreUpdate`
  audit timestamps.
- **Schema:** Hibernate `ddl-auto=validate` — **every schema change needs a Flyway
  migration** (current head: V9). No FK on derived/computed/config tables; application
  guarantees integrity (a FK would break when projections land before the player row's
  full lifecycle). FKs are fine on true child tables (e.g. `player_stats`).
- **Enums in DB:** `@Enumerated(EnumType.STRING)` always. **Never `ORDINAL`** (reordering
  a constant silently remaps every row).
- **Scoring math:** `BigDecimal` via the **String constructor** (`new BigDecimal("0.04")`),
  `HALF_UP` at scale 2, null-safe multiplication. Never the `double` constructor.
- **LLM calls:** `ChatClient` + `BeanOutputConverter<T>`. Keep a separable `parse()` seam.
  Structured output is best-effort — **wrap the parse and handle failure explicitly**;
  do not assume the schema is enforced. Model/prompt/tunables live in a `@Validated`
  `@ConfigurationProperties` bean; prompts are externalized, not `static final`.
- **Never hold a DB transaction across an LLM call.** Phase the work: READ → REASON
  (no txn) → WRITE (`@Transactional`, separate bean) → re-derive touched rows.
- **HTTP:** `RestClient` idiom for new clients. (`SleeperClient` is still on
  `java.net.http` — migrate only when explicitly asked.)
- **JSONB:** map via `@JdbcTypeCode(SqlTypes.JSON)` on a typed object (e.g.
  `source_payload`). Store the typed object mapped from the wire, never raw wire data.
- **Config-driven boundaries over data-presence inference** (e.g.
  `readoption.current-season=2026`, not "route by which rows have stats").

## Testing

Four slice patterns, risk-based: plain unit (pure cores, no Spring) · Mockito service
(`@MockitoBean`, **never** the deprecated `@MockBean`) · `@WebMvcTest` controller slices ·
`@DataJpaTest` + Testcontainers on the real `pgvector/pgvector:pg16` image (singleton base
container, `TestFixtures` factory). **Never call the live model in a test** — stub the
typed LLM output and test the application logic around it.

## Build / run / verify

- Build & test: `./mvnw clean verify`
- DB up: `docker compose up -d` (Postgres on 5433)
- Migrations run on app start (Flyway). Connect: `psql -h localhost -p 5433 -U <user> readoption`
- **Verify empirically — query the DB directly; don't infer correctness from filter
  counts or expected behavior.**

## Scoring regression anchors (must not move on any refactor)

Saquon Barkley `player_id=4866`: **208.50 / 226.00 / 243.50** (Standard / Half-PPR / PPR).
Mahomes `player_id=4046`: league rule is **−2 per INT** (not −1; provider points use −1).
JSN `player_id=9488`. If a "refactor" changes these numbers, stop — it's a correctness bug.

## Workflow rules

- **Never `git commit` (or push) unless the owner explicitly instructs it or has
  approved the commit.** Stage and report; the owner commits. A spec saying "keep
  this as its own commit" describes commit *boundaries*, not permission to commit.
  When a commit IS approved, the message is a **single line** — no body.
- **Route by blast radius, not line count.** Own load-bearing logic and its review;
  delegate scaffolding.
- **Keep a correctness fix and a standardization refactor in separate commits.**
- When a spec hands you a multi-type boundary (e.g. an LLM-output type that is
  deliberately *not* the engine's input type), **do not collapse it to "simplify."**
  Those splits are safety boundaries, not accidental indirection.
- Provide complete code including imports/getters/setters. Don't skip boilerplate.

## Pointers

- `LEARNING_LOG.md` — running narrative of decisions/rationale (read for "why",
  don't duplicate it here).
- `docs/specs/` — per-phase build specs (the transient task layer).
