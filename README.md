# Read Option

AI-powered NFL fantasy football draft assistant. Read Option aggregates player
projections from multiple providers, reconciles their disagreements into a
consensus, scores them under any league's rules, ranks players against both
their own projected value and the draft market, and reasons over that data with
an LLM agent to recommend picks during a live draft.

**Tagline:** *Read the data. Make the call.*

The guiding architecture is a clean division of labor:

> **Data providers predict, Java scores, the LLM strategizes.**

Player-performance prediction is a solved problem at scale, so projections are
consumed from established providers rather than reinvented. A deterministic Java
engine converts those projections into fantasy points under any scoring format,
ranks them, and decides every number. The LLM's job is the part that actually
needs judgment — classifying *which* provider to trust when they disagree, and
strategic draft reasoning over numbers the engine already computed. The LLM
never produces a stat or a point: it classifies and strategizes, and every
number it quotes is a verbatim engine or tool result.

## Status

**Phase 1 — Data Foundation: complete.** Pipeline, scoring engine, ranked read API.

**Phase 2 — Projections Aggregator: complete.** The first phase where the LLM
does real work. Two providers (rotowire via Sleeper, ESPN) land in a raw staging
table; a deterministic engine scores each source and routes genuine
disagreements by points dispersion; an LLM classifies the contested cases —
grounded in each player's recent actual production (the first retrieval step) —
into a verdict the engine applies. Built, run on live 2026 data, and validated:
injecting prior-season actuals moved the model from 94% abstention to a
discriminating distribution with reasoning auditable down to the retrieved fact
that moved each verdict.

**Phase 3 — Natural-language league customization: complete.** A user describes
their league and draft style in plain English; an LLM parses it into a
validated spec (`parse → refine → confirm`); a deterministic resolver turns the
spec into the engine's scoring config; confirm persists it. The LLM translates
intent to structure and never originates a scoring number — the TE-premium
bonus and preset defaults live only in the resolver's registry, and the parse
types give the model no field to write a number into. Full walkthrough, class
map, and copy-pasteable curl examples:
**[docs/phase-3-overview.md](docs/phase-3-overview.md)**.

**Phase 4 — AI draft assistant: complete.** The architectural inversion from
pre-injection RAG to **agentic tool calling**: the model decides mid-reasoning
what it needs and requests it against schemas the Java code publishes — and it
still never originates a number. A persisted draft domain (sessions, an
insert-only pick ledger, snake-order arithmetic) and a deterministic
**VORP/replacement-level engine** turn the confirmed league's roster shape into
a scarcity-adjusted draft board; a hand-owned agent loop (`ChatModel` +
`ToolCallingManager`, loud iteration cap, per-round-trip token/latency
instrumentation) exposes **six session-bound read-only tools** — draft state,
VORP board, player profile, name→id lookup, team/depth-chart room, and vector
news search over an embedded ESPN news corpus (pgvector + OpenAI embeddings).
Built starve-then-retrieve: each tool graduated only after a failure transcript
demonstrated the need. Mid-phase the platform migrated to Java 21 / Spring Boot
4 / Spring AI 2.0. Full walkthrough, concepts, tables, and run examples:
**[docs/phase-4-overview.md](docs/phase-4-overview.md)**.

**Phase 5 — live Sleeper draft sync: in progress.** 5.0 is complete and
live-verified against real Sleeper bot drafts: link a Sleeper draft to a
confirmed league config and a background loop (one virtual thread, 3s cadence)
mirrors every pick into the session ledger automatically — deferred session
creation from the draft's own facts, an idempotent set-difference sweep that
makes crash-restart-relink a non-event, snake-arithmetic cross-checks that
halt loudly on anything unmodeled (traded picks, keepers, 3RR), and a
completion count gate that refuses to mark a draft COMPLETE until the pick
count honestly equals teams × rounds (added by 5.0-b after Sleeper flipped a
live draft to complete with 166 of 168 picks settled). Manual picks on a
synced session are rejected — the poll loop is the single writer. Draft-night
operations manual: **[docs/phase-5-runbook.md](docs/phase-5-runbook.md)**.

Next up: **Phase X — in-season management** (weekly updates, lineup decisions).

## What it does today

- **Ingests** ~3,200 active fantasy-relevant NFL players, six seasons of
  historical stats, and current-season projections from **two** providers —
  rotowire (via the Sleeper API) and ESPN — into a per-source landing table.
- **Resolves** each ESPN player to a canonical player record via a deterministic
  id crosswalk (DynastyProcess `db_playerids`), with a logged review queue for
  the residual (all non-startable units — D/ST, kickers, late rookies).
- **Scores** every player under all six common scoring formats
  (Standard / Half-PPR / PPR × 4pt or 6pt passing TD) with a deterministic
  `BigDecimal` engine — the same engine scores historical actuals, projections,
  and each source's line during reconciliation.
- **Reconciles** the two providers into a single consensus mart:
  - scores each source's stat line in a measuring-stick format and computes the
    points **coefficient of variation** as a disagreement signal;
  - agreeing players take a deterministic per-stat **median**;
  - contested players are classified by an **LLM** (Spring AI +
    `BeanOutputConverter`) into a verdict — *trust consensus / favor the high
    source / favor the low source / flag uncertain* — which the engine applies as
    a selection rule over real source stat lines. The model is grounded with each
    player's recent actual production (retrieved from `player_stats`) and emits
    no numbers, only the verdict;
  - every decision is recorded in an audit table (cv, route, verdict, confidence,
    rationale, model) so any projection is explainable after the fact.
- **Ranks** players via SQL window functions: each player carries both a
  *value rank* (by the engine's projected points) and a *market rank* (by
  average draft position), positionally and overall. The gap between the two —
  "the engine values him as RB5, the market drafts him RB15" — is the draft
  edge later phases will reason about.
- **Serves** a paginated, filterable read API over the scored data, with
  RFC 9457 structured error responses and request validation.
- **Customizes** to any league described in plain English: an LLM parses the
  description into a validated spec, a stateless repair loop
  (`parse → refine → confirm`) surfaces blocking issues and drift, and a
  deterministic resolver produces the persisted league config — see
  [docs/phase-3-overview.md](docs/phase-3-overview.md).
- **Values** every draftable player against the confirmed league's roster shape:
  a deterministic **VORP engine** (greedy flex/superflex absorption →
  per-position replacement levels → `points − replacement`) scores the
  consensus mart in memory under the league's resolved rules and serves a
  VORP-ranked board with the format-matched ADP alongside.
- **Tracks** live drafts: sessions snapshot `teamCount`/`totalRounds` from the
  config at creation, every pick (yours and opponents') lands in an insert-only
  ledger with server-assigned pick numbers, and snake-order team assignment is
  derived arithmetic, never stored.
- **Syncs** a live Sleeper draft into that ledger hands-free: a background
  poll loop creates the session on the first `drafting` observation (teams,
  rounds, and your slot taken from the draft object itself), mirrors picks by
  set-difference (restart/relink recovers exactly what's missing), cross-checks
  every pick against snake arithmetic, and only marks COMPLETE when the count
  equals teams × rounds — short counts keep polling under a bounded grace, then
  fail loud. Manual picks on a synced session are refused (single writer).
- **Advises** on the clock: a conversational agent (`POST /advise`) runs a
  manual tool-calling loop over six session-bound read-only tools — draft
  state, VORP board, player profile (history + projection + role/injury/bye/
  experience), name→id lookup, team depth-chart room, and dated news search.
  Session and scoring rules are constructor-bound fields, so the generated tool
  schemas give the model no parameter to reach another session or format —
  proven by a test that parses the schemas.
- **Retrieves** the *why* behind the numbers: ESPN Rotowire news lands verbatim
  in a permanent landing table, a derived pgvector table holds OpenAI
  embeddings (deterministic ids, generation-tagged, rebuildable), and the
  agent's `searchPlayerNews` tool returns dated items filtered to one player —
  citations state the publication date; a missing corpus degrades to a loud
  label, never speculation.

## Reconciliation in one paragraph

Projection sources land raw, one row per source per player-season. The
deterministic engine produces every number; the LLM only *classifies* a
disagreement and never emits a stat. Routing is on the coefficient of variation
of the sources' fantasy points (normalized so one threshold means the same thing
across a 300-point QB and a 40-point TE). The model reasons over the per-stat
breakdown *and* the player's recent actuals — so it judges whether a higher
projection implies a role the player has never held — and returns an enum the
engine applies by selecting a real source's stat line or a per-stat median.
Reconciliation runs as an offline batch endpoint (it never sits on a request
path), phased so no database transaction is ever held across a model call.

## Stack

- Java 21
- Spring Boot 4.0.x
- **Spring AI 2.0.x** — Anthropic (official-SDK-backed) for verdict
  classification (Phase 2), league parsing (Phase 3), and the draft agent's
  tool-calling loop (Phase 4); OpenAI `text-embedding-3-small` +
  `PgVectorStore` for the news RAG (Phase 4.4)
- Jackson 3 (`tools.jackson`; annotations stay `com.fasterxml.jackson.annotation`)
- PostgreSQL 16 + pgvector (Docker) — the vector extension does real work
  since Phase 4.4 (`news_embedding`, HNSW cosine index)
- Spring Data JPA + Hibernate
- Flyway (versioned schema migration)
- Sleeper API (rotowire projections, stats, players, ADP) and the ESPN APIs —
  fantasy (projections via an `X-Fantasy-Filter` header), site (schedules),
  and player news
- DynastyProcess `db_playerids` crosswalk (bundled snapshot) for ESPN id resolution
- commons-csv, spring-boot-starter-validation
- Testcontainers 2.x (`pgvector/pgvector:pg16`) for repository slices
- Maven (via Maven Wrapper)
- Lombok

## Required environment variables

- `ANTHROPIC_API_KEY` — required for the reconciliation verdict step (Phase 2+),
  for league parsing (`/api/league/parse` and `/refine` make a live model
  call; `/confirm` does not), and for draft advice
  (`/api/draft/sessions/{id}/advise`). The dry-run calibration mode does not
  need it; the real reconcile run does. Get one at https://console.anthropic.com
- `SPRING_AI_OPENAI_API_KEY` — required since Phase 4.4: the OpenAI embedding
  model bean is constructed eagerly, so **the app does not boot without it**.
  It is spent only by `/api/news/embed` (and by query embedding inside the
  agent's news search).

## Running

```bash
# 1. Start PostgreSQL (+ pgvector) in Docker
docker compose up -d

# 2. Run the application (Flyway applies migrations on startup)
./mvnw spring-boot:run
```

The app runs on port 8080. PostgreSQL is mapped to host port **5433**, not the
default 5432 — this avoids a conflict on Windows/WSL setups where WSL already
relays 5432. If you change it, update `spring.datasource.url` in
`application.properties` to match.

`spring.jpa.hibernate.ddl-auto` is set to `validate`: Flyway owns the schema,
Hibernate only confirms the entity mappings match it and never modifies the
database.

## Loading data

The ETL endpoints are idempotent (re-running upserts rather than duplicating):

```bash
# Players (run first — stats and projections reference players)
curl -X POST http://localhost:8080/api/players/sync

# Resolve ESPN ids onto canonical players (enriches player.espn_id)
curl -X POST http://localhost:8080/api/players/sync-espn-ids

# Historical stats for a completed season (repeat per season)
curl -X POST http://localhost:8080/api/stats/sync/2024

# Projections into the raw landing table — rotowire (Sleeper) and ESPN
curl -X POST http://localhost:8080/api/projections/sync/2026     # rotowire -> raw
curl -X POST http://localhost:8080/api/projections/sync/espn     # ESPN -> raw
```

Then reconcile the raw sources into the consensus mart:

```bash
# Dry run: returns the CV distribution for threshold calibration.
# No writes, no model calls, no re-score.
curl -X POST "http://localhost:8080/api/projections/reconcile/2026?dryRun=true"

# Real run: classifies contested players via the LLM, writes the consensus
# mart, re-scores the touched players. Requires ANTHROPIC_API_KEY.
curl -X POST "http://localhost:8080/api/projections/reconcile/2026"
```

Then the Phase 4 context loads (schedule/byes, news corpus, embeddings):

```bash
# NFL schedule + derived bye weeks (ESPN site API, WSH->WAS crosswalk)
curl -X POST http://localhost:8080/api/teams/schedule/sync

# ESPN Rotowire news into the permanent landing table (several minutes;
# idempotent; re-run ~2x/week through camp — rolled-off items are unrecoverable)
curl -X POST http://localhost:8080/api/news/sync

# Embed the corpus into pgvector (OpenAI; idempotent anti-join, chunked with
# backoff — safe to re-run after a vendor outage, it continues from the gap)
curl -X POST http://localhost:8080/api/news/embed
```

## API

```
GET  /api/players/{id}/profile?format=STANDARD_6PT
        Player profile: scoring history, current projection, positional rank.

GET  /api/scoring/leaderboard?season=2026&format=STANDARD_6PT&position=RB&active=true&page=0&size=25
        Paginated leaderboard, sorted by fantasy points. Optional position and
        active-player filters.

GET  /api/scoring/leaderboard/ranked?season=2026&format=STANDARD_6PT&position=RB&active=true&page=0&size=25
        As above, plus value-rank and market-rank columns (positional and
        overall) computed with window functions.

POST /api/projections/sync/{season}
        Land rotowire (Sleeper) projections into the raw staging table.

POST /api/projections/sync/espn
        Land ESPN projections into the raw staging table (season-pinned).

POST /api/projections/reconcile/{season}?dryRun={bool}
        Reconcile the raw sources into the consensus mart. dryRun=true returns
        the CV distribution for calibration without writing or calling the model;
        a real run returns a five-count report (consensus / llm / single-source /
        fallback / skipped).

POST /api/league/parse      { description }
POST /api/league/refine     { current, correction, turn }
POST /api/league/confirm    { current }
        Natural-language league customization (Phase 3). Stateless: the parsed
        object rides in the payloads. parse/refine call the model and return
        the parsed spec + validation issues + READY/NEEDS_INPUT; confirm is the
        only writer (409 + issues while anything BLOCKING remains). Request/
        response shapes and worked examples: docs/phase-3-overview.md.

POST /api/draft/sessions                  { leagueConfigId, userSlot }
        Start a draft session against a confirmed league config. teamCount and
        totalRounds are snapshotted from the config, never request fields.

POST /api/draft/sessions/{id}/picks       { playerId }
        Record a pick (yours or an opponent's). Server-assigned pick number;
        round/team derived by snake arithmetic; 409 on a duplicate player.

GET  /api/draft/sessions/{id}/state
        Live draft state: current pick/team, your roster (with byes), unfilled
        slots, picks until your next turn, opponents' positional counts in
        the gap.

GET  /api/draft/sessions/{id}/board?position=RB&limit=20
        VORP-ranked board of available players under this league's rules, with
        per-position replacement levels and format-matched ADP.

POST /api/draft/sessions/{id}/advise      { message }
        One conversational turn with the draft agent (live model call, up to 8
        tool iterations). Returns { advice, iterations, totalTokens,
        latencyMs }. Session-scoped memory recalls prior turns.

POST /api/sleeper/sync                    { draftId, leagueConfigId }
        Link a live Sleeper draft (202; loop starts). WATCHING until the draft
        starts, then the session is created and picks mirror automatically.
        Relinking after a crash/stop/error is the recovery move — idempotent
        catch-up. Requires readoption.sleeper.username set locally.

GET  /api/sleeper/sync/{draftId}
        Sync status: { draftId, state, sessionId, picksSynced, lastPollAt,
        error }. States: WATCHING / SYNCING / COMPLETE / STOPPED / ERROR.

POST /api/sleeper/sync/{draftId}/stop
        Cooperative stop; session and picks are kept. Relink to resume.

POST /api/teams/schedule/sync?season=2026
        ESPN schedule into team_schedule + derived bye weeks (delete-and-reload
        per team, loud bye derivation).

POST /api/news/sync
POST /api/news/embed
        News RAG ingestion (Phase 4.4): sync lands ESPN Rotowire items verbatim
        (insert-only, permanent); embed builds the derived pgvector table via
        an idempotent anti-join. Deliberately separate — ingestion never waits
        on a vendor. Full walkthrough: docs/phase-4-overview.md.
```

All error responses follow **RFC 9457** (`application/problem+json`): a
structured body with `type`, `title`, `status`, `detail`, and `instance`.
Invalid parameters return a structured `400`; unknown players return `404`; an
upstream provider failure returns `502` (the dependency failed, not this server).

## Data model

Schema is managed by Flyway migrations in `src/main/resources/db/migration`:

| Migration | Table | Notes |
|-----------|-------|-------|
| V1 | `player` | Source-of-truth player records (later gains `espn_id`) |
| V2 | `player_stats` | Composite PK `(player_id, year)`, FK to `player` |
| V3 | — | Adds `fumbles_lost` to `player_stats` |
| V4 | `player_scoring` | Computed table: 3-col PK, **no FK** (app-guaranteed integrity, fully recomputable) |
| V5 | `player_projections` | The **consensus mart** — per-format ADP, source provenance, FK to `player`; written only by reconciliation |
| V6 | `player_projection_raw` + `player.espn_id` | Per-source projection landing table, PK `(player_id, year, source)`, **no FK**; `espn_id` + index on `player` |
| V7 | `player_projection_raw` | Stat columns `INTEGER → NUMERIC(7,2)` — carry fractional projections so rounding noise doesn't corrupt the cross-source dispersion signal |
| V8 | `player_projections` | Stat columns `INTEGER → NUMERIC(7,2)` — the mart receives a real fractional source line or a median; integer columns would re-truncate |
| V9 | `player_projection_reconciliation` | Per-player audit row (cv, route, verdict, confidence, rationale, model), **no FK** |
| V10 | `league_config` | Confirmed league config: **resolved** scoring as typed `NUMERIC(4,2)` columns, roster columns, nullable playoff columns, `tactics JSONB`, **no FK** (no user table yet); written only by `/api/league/confirm` |
| V11 | ADP columns | Per-format ADP: raw table swaps single `adp`/`adp_format` for `adp_std`/`adp_half_ppr`/`adp_ppr` `NUMERIC(6,2)`; mart columns widened to match. ADP copied verbatim rotowire raw → mart, never derived |
| V12 | `draft_session` + `draft_pick` | Sessions (IDENTITY, frozen `team_count`/`total_rounds` snapshots) and the insert-only pick ledger: composite PK `(session_id, overall_pick_no)`, **real FKs** to session and player, `UNIQUE (session_id, player_id)`; no `team_no` column — snake assignment is derived |
| V13 | `nfl_team` + `team_schedule` + `player` | Teams (Sleeper abbrev PK, `espn_abbrev` crosswalk, derived `bye_week`), schedule (PK `(team, season, week)`, no FK — landing), five raw-vocabulary depth-chart/injury columns on `player` |
| V14 | `nfl_team` seed | 32 teams, no stale OAK, WAS/WSH the single crosswalk row (DDL/DML split deliberate) |
| V15 | `player_news` | News landing: insert-only, verbatim, permanent (source retention is opaque — this is the only durable record); `published TIMESTAMPTZ` is the citation fact; `source_payload JSONB`; **no FK** |
| V16 | `news_embedding` | The pgvector table (+ `CREATE EXTENSION vector`): deterministic UUID id, `content`, `metadata JSONB`, `embedding VECTOR(1536)`, HNSW cosine index. Owned by Spring AI's `PgVectorStore` (no JPA entity); the bean boots with validation on, so schema drift fails at startup |
| V17 | `player_news` | PK widened to `(source, news_id, player_id)` — one item appears in several players' feeds; the two-column PK silently collapsed those associations |
| V18 | `draft_session` | Adds nullable `sleeper_draft_id` + UNIQUE — binds a session to a live Sleeper draft; null means a manual session, and the relink finder keys on it |

Design principle: foreign keys on tables holding external source data that
references real entities (`player_stats`, `player_projections`) and on true
transactional child rows (`draft_pick`); no foreign key on the computed scoring
table, the per-source landing tables, the audit table, or config tables — all
populated by application logic and recomputable (or deliberately
user-table-less). A landing table also can't carry a FK that would abort an
ingest batch on an unresolved row.

## Tests

Risk-based coverage — domain logic, custom SQL, and web wiring are tested
thoroughly; framework-generated code (Lombok accessors, inherited Spring Data
CRUD) is not. Four test patterns:

- **Plain unit** — the scoring engine and the pure reconciliation cores
  (`DispersionCalculator`, `ConsensusBuilder`), tested with no Spring context.
- **Mockito service** — service logic in isolation; the verdict-application
  mapping is tested with a stubbed `Verdict` (every enum → the correct selected
  line, fallback on a thrown classifier), so the routing is covered without a
  live model.
- **`@WebMvcTest`** — controller slices with `MockMvc`: status codes, JSON
  shape, parameter validation.
- **`@DataJpaTest` + Testcontainers** — repository queries and upserts run
  against the same `pgvector/pgvector:pg16` image used in production. Custom SQL
  (cross-entity joins, window functions, `CASE` expressions, null semantics) is
  verified on real PostgreSQL, since an in-memory database can report different
  results — the ranked-leaderboard test pins `RANK()` tie-and-skip behavior and
  null handling that H2 would get wrong; the reconciliation-writer test pins
  upsert idempotency, ADP preservation, and touched-only re-score; the vector
  retrieval slice (fake 1536-dim embedding model, real pgvector) pins the
  one-player filter and that old-generation embeddings never surface.

Phase 4 adds two safety-property tests worth naming: the **schema test** parses
the generated JSON schema of all six agent tools and asserts each exposes
exactly its documented parameters (`sessionId`/`scoringRules` unreachable by
construction), and the agent loop is tested with a **stubbed `ChatModel`**
(scripted tool-call responses) — never a live model.

```bash
./mvnw test
```

## Roadmap

- [x] Phase 0 — LLM fundamentals, Spring AI basics (see playground repos)
- [x] Phase 1 — Data foundation: ETL pipeline, scoring engine, ranked read API
- [x] Phase 2 — Projections aggregator: multi-source ingestion, dispersion
  routing, LLM-assisted reconciliation grounded in prior-season actuals
  (the first retrieval step)
- [x] Phase 3 — User customization: natural-language league descriptions →
  validated spec → deterministically resolved scoring rules
  ([walkthrough](docs/phase-3-overview.md))
- [x] Phase 4 — AI draft assistant: draft domain + VORP engine, agentic tool
  calling with six session-bound tools, team/schedule/depth-chart context,
  vector news RAG, Java 21 / Boot 4 / Spring AI 2.0 migration
  ([walkthrough](docs/phase-4-overview.md))
- [ ] Phase 5 — Live Sleeper draft sync: **5.0 complete** (poll loop, deferred
  session creation, set-difference mirroring, count-gated completion,
  single-writer guard — [runbook](docs/phase-5-runbook.md)); next: 5.1 K/DEF
  roster modeling
- [ ] Phase X — In-season management: weekly updates, lineup decisions

## License

MIT
