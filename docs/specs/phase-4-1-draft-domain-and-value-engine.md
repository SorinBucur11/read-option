# Phase 4.1 Spec — Draft Domain + Deterministic Value Engine

**Transient task artifact for `docs/specs/`. Not committed as permanent docs.**
Conventions (Persistable upsert, no-FK rules, sync-service shape, test slices,
RFC 9457 handling) live in `CLAUDE.md` — follow them; this spec defines *what*
and the decisions, not restated conventions.

## Context

Phase 4 builds the tool-calling draft agent. Increment 4.1 is the deterministic
substrate the agent's most load-bearing tools will wrap: persisted draft state
and a VORP-ranked draft board computed from the confirmed league's roster shape.
**No LLM code in this increment.** The agent loop (4.2) consumes these via tools.

Core boundary (unchanged from Phases 2–3): the engine computes every number
(scores, replacement levels, VORP, pick arithmetic); the LLM will later reason
over those numbers and never originate one.

## Scope

**In:** per-format ADP promotion into the mart (Task 0, conditional);
`draft_session` + `draft_pick` persistence; snake-order derivation;
`DraftService` with server-assigned pick sequencing; `DraftStateView` with
opponent-roster dimension; league_config → domain mapping; replacement-level /
VORP engine; draft-board read model + endpoints; full test coverage per the
established slice patterns.

**Out (later increments):** everything LLM (tools, ChatClient, ChatMemory — 4.2);
teams/schedule/depth-chart ingestion (4.3); news/vector RAG (4.4); stacking
graduation (4.5); traded picks / keeper leagues (v1 assumes pure snake); K/DST
positions; Sleeper live-draft sync (design leaves room: `draft_pick` records
observed picks from any writer).

---

## Task 0 — Per-format ADP in the mart (verify, then branch)

**First, inspect the repo:** does `player_projections` already carry three
per-format ADP columns (standard / half-PPR / PPR), populated? Check the entity,
the migrations, and the reconciliation writer.

**Branch A — columns exist and are populated:** skip to Deliverable 1; migration
numbering starts at the next free version.

**Branch B — not built (expected, per the deferred list):** implement the
carried-forward design as **its own commit**, before and separate from the draft
work:

1. **Migration (next free version):** on `player_projection_raw`, drop `adp` and
   `adp_format`; add `adp_standard`, `adp_half_ppr`, `adp_ppr` — `NUMERIC(6,2)`,
   nullable. Same three columns on `player_projections` (nullable).
2. **Rotowire/Sleeper mapper:** map the three ADP fields present in the Sleeper
   projections payload (`adp_std`, `adp_half_ppr`, `adp_ppr` — verify exact
   field names against the typed payload object / a live response) onto the raw
   row. ESPN mapper: leaves them null (no ADP in that source).
3. **Reconciliation writer:** copy the three values **verbatim from the rotowire
   raw row** onto the mart row for every player written — regardless of route or
   verdict. ADP is an observed market fact, never derived, never
   verdict-following. Null in raw → null in mart.
4. **Backfill:** re-run the rotowire sync (re-lands raw with ADP), then re-run
   reconciliation for 2026. Expect the same five-count report shape as the
   Phase 2 run; verify in psql that mart ADP columns are populated for the bulk
   of the 310 players.
5. **Entity updates** on both entities; raw `source_payload` scoping unchanged.

---

## Deliverable 1 — Migration: `draft_session` + `draft_pick`

Next free version number after Task 0. Shape (adjust `player_id` type to match
`player.id` exactly):

```sql
CREATE TABLE draft_session (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    league_config_id BIGINT       NOT NULL,   -- logical ref, no FK (config table convention)
    season           INT          NOT NULL,
    team_count       INT          NOT NULL CHECK (team_count BETWEEN 2 AND 20),
    user_slot        INT          NOT NULL,
    total_rounds     INT          NOT NULL CHECK (total_rounds BETWEEN 1 AND 30),
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CHECK (user_slot BETWEEN 1 AND team_count)
);

CREATE TABLE draft_pick (
    session_id      BIGINT      NOT NULL REFERENCES draft_session (id),
    overall_pick_no INT         NOT NULL CHECK (overall_pick_no >= 1),
    player_id       <match player.id> NOT NULL REFERENCES player (id),
    picked_at       TIMESTAMP   NOT NULL,
    PRIMARY KEY (session_id, overall_pick_no),
    CONSTRAINT uq_draft_pick_player UNIQUE (session_id, player_id)
);
```

Decisions encoded here:

- **FKs are present**, unlike landing/derived/config tables: these are
  transactional source-of-truth rows (same category as `player_stats → player`).
  A pick referencing a vanished session or player is corruption, not a staging
  artifact.
- **No `team_no` column.** Snake-draft team assignment is arithmetic over
  `(overall_pick_no, team_count)` — persisting it would be storing a derivation
  that can drift from its source. Revisit only if traded picks ever enter (then
  it becomes an observed fact).
- **`total_rounds` stored, not derived at read time:** frozen at session
  creation from the config's roster shape, so a later config row can't change a
  running draft's length mid-flight.
- **`uq_draft_pick_player` is the final arbiter** of can't-draft-twice; the
  service check exists for the friendly error. Defense in depth, deliberately.

## Deliverable 2 — `draft/` package: entities, repositories

New package `app.readoption.draft`.

- **`DraftStatus`** enum: `ACTIVE`, `COMPLETE`, `ABANDONED`.
- **`DraftSession`** entity: IDENTITY id, audit `@PrePersist`/`@PreUpdate`
  timestamps, `@Enumerated(EnumType.STRING)` status. **No Persistable** —
  IDENTITY already marks new rows (same reasoning as `league_config`), and the
  id must serialize on create. Mutable in exactly one way: status.
- **`DraftPick`** entity: composite PK via the existing ID-class convention
  (`DraftPickId`: sessionId + overallPickNo). **Persistable pattern applies**
  (assigned composite key → Spring Data would otherwise SELECT-before-INSERT):
  `@Builder.Default private transient boolean isNew = true;` with `@JsonIgnore`
  on `isNew()`/`getId()` per the landing-table precedent. Insert-only — no
  update path anywhere in the service.
- **Repositories:** `DraftSessionRepository`;
  `DraftPickRepository` with `List<DraftPick> findBySessionIdOrderByOverallPickNo(long)`,
  `boolean existsBySessionIdAndPlayerId(...)`, and
  `Optional<Integer> findMaxOverallPickNo(long sessionId)` (derived or `@Query`
  — `max` needs `@Query("select max(p.overallPickNo) ...")`).

## Deliverable 3 — `SnakeOrder` (pure, exhaustively tested)

Pure final class in `draft/`, no Spring, all static or a tiny value-holder —
match the style of `DispersionCalculator`.

Semantics (1-based everywhere):

- round of pick *p*: `r = ceil(p / T)`
- index within round: `i = p − (r−1)·T`
- **`teamFor(p, T)`**: odd `r` → `i`; even `r` → `T − i + 1`
- **`overallPickFor(slot, round, T)`**: odd round → `(round−1)·T + slot`;
  even round → `(round−1)·T + (T − slot + 1)`
- **`nextPickFor(slot, currentPick, T, totalRounds)`**: the smallest overall
  pick number `> currentPick` belonging to `slot`; empty
  `OptionalInt` if none remains.
- **`picksUntilNextTurn(slot, currentPick, T, totalRounds)`**: derived from the
  above; the count of *other* teams' picks strictly between.

**Tests (`SnakeOrderTest`, plain unit):** exhaustive assertion of all 20 picks
of a T=10 two-round table against a hand-written expected array; parity
behavior at the turn (slot 10 owns picks 10 and 11; slot 1 owns 1 and 20);
slot 8 / T=10 gap fixture from the design discussion (after pick 8, next is 13,
gap picks 9–12 belong to teams 9,10,10,9); last-round exhaustion returns empty;
T=12 spot checks. This function is the substrate for opponent-roster
reconstruction — over-test it.

## Deliverable 4 — `DraftService`, `DraftStateView`, exceptions

**`DraftService`** (`@Transactional` on the writers; readers read-only):

- **`startSession(StartDraftRequest)`** — validates the referenced
  `league_config` row exists (404 → new `LeagueConfigNotFoundException` if
  none); computes `total_rounds` as the sum of all roster spots from the config
  row (QB + RB + WR + TE + flex + superflex + bench columns — read the actual
  column set from the entity); persists ACTIVE session; returns the session
  DTO. Bean Validation on the request (`teamCount` 2–20, `userSlot` ≥ 1) plus a
  cross-field service check `userSlot ≤ teamCount` (400).
- **`recordPick(sessionId, RecordPickRequest{playerId})`** — the **server
  assigns `overall_pick_no`** = (max for session) + 1; the client never sends a
  sequence number. Validations in order: session exists (404), status ACTIVE
  (409 `DraftSessionNotActiveException`), player exists (404, reuse
  `PlayerNotFoundException`), not already drafted (409
  `PlayerAlreadyDraftedException` — the unique constraint remains the backstop;
  translate a constraint violation on that index to the same 409 rather than a
  500). If the assigned pick number equals `team_count × total_rounds`, flip
  the session to COMPLETE in the same transaction. Returns the pick +
  derived team slot.
- **`getState(sessionId)`** → **`DraftStateView`** (record, compact — it is the
  dry run for a 4.2 tool result):
  - `sessionId`, `status`, `currentOverallPick` (next unmade pick),
    `currentTeamSlot`, `onTheClock` (boolean: is it the user),
    `picksUntilUserNextTurn`
  - `userRoster`: list of `{playerId, name, position, round}`
  - `unfilledSlots`: map position/slot-type → remaining count, computed against
    the session's config roster shape (flex/bench counted as their own keys)
  - `gapTeams`: for each *distinct* team slot picking strictly before the
    user's next turn, `{teamSlot, picksInGap, positionalCounts}` where
    `positionalCounts` is a map Position → int of that team's roster so far
    (derived: group all picks by `SnakeOrder.teamFor`, join player positions).
    Counts only — no player dumps; tool-result budget discipline starts now.

**Exceptions → `GlobalExceptionHandler`:** `DraftSessionNotFoundException`
(404), `DraftSessionNotActiveException` (409), `PlayerAlreadyDraftedException`
(409, carries playerId and the pick number that took him),
`LeagueConfigNotFoundException` (404). RFC 9457 shape per the existing handler.

## Deliverable 5 — `LeagueConfig` → domain mapping (+ ADP bucket for custom rules)

The valuation engine needs the confirmed config as domain objects. Precedent:
`ScoringFormat.toScoringRules()`.

- On the `LeagueConfig` entity: **`toScoringRules()`** (typed NUMERIC columns →
  the `ScoringRules` value object via its `of(...)` factory) and
  **`toLeagueSettings()`** (roster columns + flex-eligible CSV via the existing
  converter + superflex). Pure mapping, no defaults invented — the row is
  already resolved; nulls that can't occur post-resolution should throw
  `IllegalStateException`, not silently default.
- **`AdpBucket.forReceptionPoints(BigDecimal)`** — nearest-bucket mapping for
  custom scoring: `< 0.25` → STANDARD, `< 0.75` → HALF_PPR, else → PPR.
  Compare with `compareTo`. Presets keep using `ScoringFormat.adpBucket()`;
  this is the custom-rules path only. Unit-test the boundary values (0, 0.25,
  0.5, 0.74, 0.75, 1).

## Deliverable 6 — `valuation/` package: `ReplacementLevelCalculator` (pure)

New package `app.readoption.valuation`. Pure core / orchestrator split, same as
reconciliation.

**`ReplacementLevelCalculator`** — no Spring, no I/O. Input: per-position lists
of `PlayerValue(playerId, position, points)` **already scored** (BigDecimal),
plus `teamCount` and the roster shape (`LeagueSettings`). Output:
`Map<Position, BigDecimal>` replacement level per position.

Algorithm (deterministic, greedy):

1. Sort each position's list by points desc (comparator by `compareTo`,
   tie-break playerId for determinism).
2. **Dedicated starters:** per position, reserve the top
   `teamCount × starters(position)` players.
3. **Flex absorption:** pool all *unreserved* players whose position is in the
   flex-eligible set; take the top `teamCount × flexSlots` by points; count
   how many landed per position; add to each position's reserved count.
4. **Superflex:** same greedy pass over the still-unreserved with QB included
   in the eligible set, `teamCount × superflexSlots` (0 slots → no-op).
5. **Replacement level(position)** = points of the player at index
   `reservedCount` (0-based) in that position's sorted list — the best player
   *outside* the startable pool. If the list is shorter than
   `reservedCount + 1`, replacement level = `BigDecimal.ZERO` (a position so
   shallow the baseline is a zero — log at WARN, don't throw).
6. Bench slots do **not** extend the baseline (starters-only definition; bench
   value is judgment, left to the LLM later).

**Tests (`ReplacementLevelCalculatorTest`, plain unit, fixture lists):**

- dedicated-only shape (0 flex): baseline is exactly the `count`-th player
- **flex absorption shifts a baseline** — a fixture where adding one flex slot
  moves the RB replacement index by the number of RBs absorbed (assert the
  *transformed* value, boundary-test discipline: the asserted number must be
  impossible under dedicated-only math)
- flex eligibility respected (a QB never absorbed by a non-superflex flex)
- superflex absorbs a QB when QB points dominate the pool
- short position list → ZERO + no exception
- all BigDecimal assertions by `compareTo` (scale-insensitive)

## Deliverable 7 — `DraftBoardService` (orchestrator) + board DTO

`@Service`, read-only. `getBoard(sessionId, optional Position filter, limit
default 20, max 50)`:

1. Load session (404 if absent) → load its `league_config` row →
   `toScoringRules()` / `toLeagueSettings()`.
2. Load the season's mart rows (`player_projections`, they are `Scorable`).
   **Score in-memory** per player via the existing
   `ScoringService.calculate(statLine, rules, position)` — position threaded so
   a TE-premium config applies correctly. **No preset short-circuit** and no
   reads from `player_scoring`: one uniform path for preset and custom leagues;
   ~400 `calculate` calls are trivial. (Decision recorded against the
   phase-3-overview §5.1 note — the short-circuit stays deferred until a read
   path exists where precomputation pays.)
3. Compute replacement levels once per request via the calculator (static
   pre-draft baseline — deliberately *not* recomputed over the drained pool;
   the board drains, season-long scarcity doesn't).
4. `vorp = points − replacementLevel(position)`.
5. Exclude drafted players (the session's pick set).
6. Select the ADP column matching the league: preset path
   `ScoringFormat.adpBucket()` where applicable, custom path
   `AdpBucket.forReceptionPoints(rules' reception points)`.
7. Rank by VORP desc (`compareTo`), tie-break ADP asc (nulls last), then
   playerId. Return `DraftBoardView`: `{season, replacementLevels,
   rows: [{playerId, name, position, projectedPoints, vorp, adp}]}`. Compact;
   no stat-line dumps — this DTO is the prototype for the 4.2 tool result.

Players absent from the mart are simply not on the board (no projection = not
draftable). Positions in the roster shape with no projection rows (K/DST):
skip with one WARN log, board still serves.

**Tests:** Mockito service test with fixture mart rows and a known rules object
— assert the expected VORP ordering and values by `compareTo`; assert a drafted
player is absent; assert the ADP bucket selection for a custom half-PPR-ish
config (reception points 0.5) vs a standard preset.

## Deliverable 8 — `DraftController` + web slice

- `POST /api/draft/sessions` → 201, session DTO
- `POST /api/draft/sessions/{id}/picks` body `{playerId}` → 201, pick DTO
  (with derived team slot)
- `GET /api/draft/sessions/{id}/state` → `DraftStateView`
- `GET /api/draft/sessions/{id}/board?position=&limit=` → `DraftBoardView`

`@WebMvcTest` slice: happy paths, 404 unknown session, 409 duplicate player,
409 pick after COMPLETE, 400 bean-validation failure (teamCount=1), RFC 9457
bodies asserted.

**`@DataJpaTest` (Testcontainers, pgvector image):** session round-trip incl.
generated id + status update + audit timestamps; pick insert; **duplicate
`(session, player)` insert violates `uq_draft_pick_player` at flush** (assert
the constraint fires — the backstop must be proven real, not assumed);
`findMaxOverallPickNo` on empty and populated sessions.

---

## Commit plan (route by blast radius)

1. **ADP promotion** (Task 0 Branch B only): migration + mappers + writer +
   entity fields + backfill verification. Standalone — it touches the
   reconciliation surface and nothing draft-related.
2. **Draft domain:** migration + entities + repos + `SnakeOrder` +
   `DraftService` + state view + exceptions + handler + controller (sessions,
   picks, state) + all their tests.
3. **Valuation engine:** config→domain mapping + `AdpBucket.forReceptionPoints`
   + calculator + `DraftBoardService` + board endpoint + tests.

## Review checklist (Sorin reads the diff, not the self-report)

- [ ] Migration: both CHECKs, the FK pair, `uq_draft_pick_player` present
- [ ] `recordPick`: pick number computed server-side, inside the transaction;
      no `overallPickNo` field anywhere in the request DTO
- [ ] Constraint-violation on duplicate player translated to 409, not 500
- [ ] `DraftPick.isNew` carries `@Builder.Default` (the silent-regression trap)
- [ ] No `team_no` column or field crept in anywhere
- [ ] Ranking and drift-sensitive comparisons use `compareTo`, never `equals`,
      on BigDecimal; test assertions likewise
- [ ] Replacement-level flex test asserts a value impossible under
      dedicated-only math (boundary-test rule)
- [ ] ADP written verbatim from raw — no derivation between formats, no
      verdict dependence
- [ ] `toScoringRules()` throws on impossible nulls rather than defaulting
- [ ] DTOs stay compact (counts and numbers, no entity/stat-line dumps)

## Acceptance runbook (manual, psql + curl)

1. (Branch B) after backfill: `SELECT count(*) FROM player_projections WHERE
   adp_ppr IS NOT NULL AND year = 2026;` — expect the bulk of 310.
2. Start a session against the confirmed league_config (10 teams, slot 8).
   Confirm `total_rounds` matches the config's roster spot count.
3. Record 8 picks (any distinct players). `GET /state`: pick 9 on the clock,
   team slot 9, `picksUntilUserNextTurn = 4`, `gapTeams` shows slots 9 and 10
   with 2 picks each.
4. Record a duplicate player → 409 with the original pick number in the body.
5. `GET /board?position=RB&limit=5`: VORP-descending, drafted RBs absent, ADP
   populated; cross-check one player's `projectedPoints` by hand against the
   mart row and the league's rules.
6. `GET /board` on a fresh session for a *custom* config (TE premium): a TE's
   points must reflect the 0.5 reception bonus (compare against the preset
   board — the same TE scores higher).
