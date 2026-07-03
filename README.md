# Read Option

AI-powered NFL fantasy football draft assistant. Read Option aggregates player
projections from multiple providers, reconciles their disagreements into a
consensus, scores them under any league's rules, ranks players against both
their own projected value and the draft market, and (in later phases) reasons
over that data with an LLM to recommend picks during a live draft.

**Tagline:** *Read the data. Make the call.*

The guiding architecture is a clean division of labor:

> **Data providers predict, Java scores, the LLM strategizes.**

Player-performance prediction is a solved problem at scale, so projections are
consumed from established providers rather than reinvented. A deterministic Java
engine converts those projections into fantasy points under any scoring format,
ranks them, and decides every number. The LLM's job is the part that actually
needs judgment — classifying *which* provider to trust when they disagree, and
(in later phases) strategic draft reasoning over numbers the engine already
computed. The LLM never produces a stat or a point; it classifies, and the
engine applies the verdict.

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

**Phase 3 — Natural-language league customization: built.** A user describes
their league and draft style in plain English; an LLM parses it into a
validated spec (`parse → refine → confirm`); a deterministic resolver turns the
spec into the engine's scoring config; confirm persists it. The LLM translates
intent to structure and never originates a scoring number — the TE-premium
bonus and preset defaults live only in the resolver's registry, and the parse
types give the model no field to write a number into. Full walkthrough, class
map, and copy-pasteable curl examples:
**[docs/phase-3-overview.md](docs/phase-3-overview.md)**.

Next up: **Phase 4 — AI draft assistant** (agent with tool calling, real-time
recommendations, consuming the captured league config).

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

- Java 17
- Spring Boot 3.5.14
- **Spring AI 1.1.6 (Anthropic Claude)** — structured-output verdict
  classification (Phase 2) and league-description parsing (Phase 3)
- PostgreSQL 16 + pgvector (Docker)
- Spring Data JPA + Hibernate
- Flyway (versioned schema migration)
- Sleeper API (rotowire projections, stats, players, ADP) and the ESPN fantasy
  API (projections via an `X-Fantasy-Filter` header)
- DynastyProcess `db_playerids` crosswalk (bundled snapshot) for ESPN id resolution
- commons-csv, spring-boot-starter-validation
- Maven (via Maven Wrapper)
- Lombok

## Required environment variables

- `ANTHROPIC_API_KEY` — required for the reconciliation verdict step (Phase 2+)
  and for league parsing (`/api/league/parse` and `/refine` make a live model
  call; `/confirm` does not). The dry-run calibration mode does not need it;
  the real reconcile run does. Get one at https://console.anthropic.com

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

Design principle: foreign keys on tables holding external source data that
references real entities (`player_stats`, `player_projections`); no foreign key
on the computed scoring table, the per-source landing table, or the audit table —
all populated by application logic and recomputable from scratch. A landing
table also can't carry a FK that would abort an ingest batch on an unresolved row.

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
  upsert idempotency, ADP preservation, and touched-only re-score.

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
- [ ] Phase 4 — AI draft assistant: agent with tool calling, real-time recommendations
- [ ] Phase 5 — In-season management: weekly updates, lineup decisions

## License

MIT
