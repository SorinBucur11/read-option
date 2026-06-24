# Read Option

AI-powered NFL fantasy football draft assistant. Read Option aggregates player
projections, scores them under any league's rules, ranks players against both
their own projected value and the draft market, and (in later phases) reasons
over that data with an LLM to recommend picks during a live draft.

**Tagline:** *Read the data. Make the call.*

The guiding architecture is a clean division of labor:

> **Data providers predict, Java scores, the LLM strategizes.**

Player-performance prediction is a solved problem at scale, so projections are
consumed from established providers rather than reinvented. A deterministic Java
engine converts those projections into fantasy points under any scoring format
and ranks them. The LLM's job is the part that actually needs judgment —
strategic draft reasoning over numbers the engine already computed (value vs.
market price, positional scarcity, roster construction).

## Status

**Phase 1 — Data Foundation: complete.** The data pipeline, scoring engine, and
ranked read API are built and tested. Next up is **Phase 2 — the projections
aggregator**, the first phase where the LLM does real work: reconciling
projections from multiple providers into a consensus.

## What it does today

- **Ingests** ~3,200 active fantasy-relevant NFL players, six seasons of
  historical stats, and current-season projections from the Sleeper API.
- **Scores** every player under all six common scoring formats
  (Standard / Half-PPR / PPR × 4pt or 6pt passing TD) with a deterministic
  `BigDecimal` engine — ~115k scoring rows.
- **Ranks** players via SQL window functions: each player carries both a
  *value rank* (by the engine's projected points) and a *market rank* (by
  average draft position), positionally and overall. The gap between the two —
  "the engine values him as RB5, the market drafts him RB15" — is the draft
  edge later phases will reason about.
- **Serves** a paginated, filterable read API over the scored data, with
  RFC 9457 structured error responses and request validation.

## Stack

- Java 17
- Spring Boot 3.5.14
- Spring AI 1.1.6 (Anthropic Claude) — used from Phase 2 onward
- PostgreSQL 16 + pgvector (Docker)
- Spring Data JPA + Hibernate
- Flyway (versioned schema migration)
- Sleeper API (free NFL data source)
- Maven (via Maven Wrapper)
- Lombok

## Required environment variables

- `ANTHROPIC_API_KEY` — only needed once LLM features land (Phase 2+).
  Get one at https://console.anthropic.com

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

# Historical stats for a completed season (repeat per season)
curl -X POST http://localhost:8080/api/stats/sync/2024

# Projections for the upcoming season (also triggers scoring)
curl -X POST http://localhost:8080/api/projections/sync/2026
```

## API

```
GET /api/players/{id}/profile?format=STANDARD_6PT
        Player profile: scoring history, current projection, positional rank.

GET /api/scoring/leaderboard?season=2026&format=STANDARD_6PT&position=RB&active=true&page=0&size=25
        Paginated leaderboard, sorted by fantasy points. Optional position and
        active-player filters.

GET /api/scoring/leaderboard/ranked?season=2026&format=STANDARD_6PT&position=RB&active=true&page=0&size=25
        As above, plus value-rank and market-rank columns (positional and
        overall) computed with window functions.
```

All error responses follow **RFC 9457** (`application/problem+json`): a
structured body with `type`, `title`, `status`, `detail`, and `instance`.
Invalid parameters (bad enum, negative page, oversized page size) return a
structured `400`; unknown players return `404`.

## Data model

Schema is managed by Flyway migrations in `src/main/resources/db/migration`:

| Migration | Table | Notes |
|-----------|-------|-------|
| V1 | `player` | Source-of-truth player records |
| V2 | `player_stats` | Composite PK `(player_id, year)`, FK to `player` |
| V3 | — | Adds `fumbles_lost` to `player_stats` |
| V4 | `player_scoring` | Computed table: 3-col PK, **no FK** (app-guaranteed integrity, fully recomputable) |
| V5 | `player_projections` | Per-format ADP, source provenance, FK to `player` |

Design principle: foreign keys on tables holding external source data that
references real entities (stats, projections); no foreign key on the computed
scoring table, which is always populated by application logic and can be
recomputed from scratch.

## Tests

Risk-based coverage — domain logic, custom SQL, and web wiring are tested
thoroughly; framework-generated code (Lombok accessors, inherited Spring Data
CRUD) is not. Four test patterns:

- **Plain unit** — the scoring engine, tested through an anonymous interface
  implementation with no Spring context.
- **Mockito service** — service logic in isolation; behavior verified with
  `verify(never())` and `ArgumentCaptor`, not just return values.
- **`@WebMvcTest`** — controller slices with `MockMvc`: status codes, JSON
  shape, parameter validation.
- **`@DataJpaTest` + Testcontainers** — repository queries run against the same
  `pgvector/pgvector:pg16` image used in production. Custom SQL (cross-entity
  joins, window functions, `CASE` expressions, null semantics) is verified on
  real PostgreSQL, since an in-memory database can report different results —
  the ranked-leaderboard test pins `RANK()` tie-and-skip behavior and null
  handling that H2 would get wrong.

```bash
./mvnw test
```

## Roadmap

- [x] Phase 0 — LLM fundamentals, Spring AI basics (see playground repos)
- [x] Phase 1 — Data foundation: ETL pipeline, scoring engine, ranked read API
- [ ] Phase 2 — Projections aggregator: multi-source data, LLM-assisted reconciliation
- [ ] Phase 3 — User customization: natural-language tactics → structured rules
- [ ] Phase 4 — AI draft assistant: agent with tool calling, real-time recommendations
- [ ] Phase 5 — In-season management: weekly updates, lineup decisions

## License

MIT
