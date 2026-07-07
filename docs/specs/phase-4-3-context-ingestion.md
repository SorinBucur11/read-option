# Phase 4.3 Spec — Current-Context Ingestion (SQL Retrievers)

**Status:** ready to build
**Depends on:** Phase 4.2 complete (agent loop, three read-only tools). Migration head: V12.
**Reader:** Claude Code, working in the read-option repo with CLAUDE.md conventions loaded.

---

## 1. Context and goal

The 4.2 agent runs information-starved on player roles, depth charts, injury status, and
schedule facts — it flagged these gaps itself, verbatim, in live transcripts. 4.3 lands the
**structured half** of that requirements list via deterministic SQL retrieval. The unstructured
half (offseason news) is Phase 4.4 (vector RAG) — out of scope here.

**This increment touches no Spring AI surface.** The agent loop, tool count, and tool
parameters are unchanged. 4.3 widens *what flows through* the existing tools: `get_player_profile`
and `get_draft_state` return richer views. Success is measured at the reasoning layer: advice
must cite role/bye/injury facts it previously disclaimed (acceptance runbook, §7 — owned by
Sorin, not part of this build).

---

## 2. Decisions record (settled in design — do not relitigate; deviations require reasoning per CLAUDE.md)

1. **Depth chart + injury live as five columns on `player`.** Sleeper delivers these facts
   player-shaped on the `/players/nfl` blob we already sync. A team-shaped depth chart would
   be a repeating group (1NF violation) and would duplicate player→team membership the player
   row already owns.
2. **Landing columns store the source's raw vocabulary (VARCHAR), no enums.** Audit found 32
   distinct `depth_chart_position` values (incl. `LWR`/`RWR`/`SWR`) and 9 `injury_status`
   values. The consumer is the LLM, which reads source vocabulary natively; normalization to
   the domain `Position` happens only where SQL must *group*, in the retriever query — never
   on the landing column.
3. **`nfl_team` is a thin reference table.** PK = Sleeper abbreviation (canonical — it must
   join to `player.team`). `espn_abbrev` is a crosswalk column; the audit found exactly one
   divergence (Sleeper `WAS` / ESPN `WSH`). Seeded by migration with exactly 32 rows.
   **`OAK` is deliberately excluded** — the Sleeper blob still carries active players with
   the stale pre-relocation code; it is source noise, not a team.
4. **`team_schedule` holds team-week rows**, PK `(team, season, week)`, **no FK** (landing
   semantics per convention). Each game appears twice (once per team) — matches both the
   ESPN per-team delivery shape and every read pattern. Vocabulary is Sleeper abbrevs,
   crosswalked **once at the write boundary**, so all read-side joins speak one vocabulary.
5. **`bye_week` is derived at sync time** from the schedule gap and stored on `nfl_team`
   (derived data, rebuilt on every sync — never hand-entered, never copied onto players;
   players reach it through their team).
6. **Zero new tools.** The retrieval surface is enrichment of the existing views.
   `get_team_context` is graduation-gated: build it only if the post-4.3 acceptance
   transcript shows the agent reaching for team-room context it cannot get.
7. **`depthChartAhead` is scoped to the raw sub-position.** `depth_chart_order` ranks a rung
   *within* its ladder — a team has an order-1 LWR, an order-1 RWR, and an order-1 SWR
   simultaneously. Scoping the query to normalized WR would report false competition.
8. **LEFT JOIN + loud degradation everywhere player→team is joined.** 2,199 active players
   carry `NULL` team (free agents); stale `OAK` rows may exist. An unknown or null team
   yields explicit "team context unavailable" strings in the view — never a dropped row
   (no INNER JOIN), never a silent remap of OAK→LV.
9. **ESPN schedule comes from the site API host** (`site.api.espn.com`) — a different host
   and auth posture from the fantasy API (`X-Fantasy-Filter` is NOT needed). It gets its own
   small client rather than overloading `EspnClient`.
10. **`seasonType.type == 2` filter is hard.** The events array carries preseason games
    (type 1); without the filter, preseason week numbers collide with the PK and corrupt
    bye derivation.

---

## 3. Commit A — land the facts (schema + player-side sync)

### 3.1 Migrations

**`V13__team_schedule_and_player_context.sql`** (schema only — DDL/DML split is deliberate):

```sql
CREATE TABLE nfl_team (
    abbrev       VARCHAR(5)  PRIMARY KEY,
    espn_abbrev  VARCHAR(5)  NOT NULL UNIQUE,
    name         VARCHAR(50) NOT NULL,
    bye_week     SMALLINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_schedule (
    team      VARCHAR(5) NOT NULL,
    season    SMALLINT   NOT NULL,
    week      SMALLINT   NOT NULL,
    opponent  VARCHAR(5) NOT NULL,
    is_home   BOOLEAN    NOT NULL,
    PRIMARY KEY (team, season, week)
);

ALTER TABLE player
    ADD COLUMN depth_chart_position VARCHAR(10),
    ADD COLUMN depth_chart_order    SMALLINT,
    ADD COLUMN injury_status        VARCHAR(20),
    ADD COLUMN injury_body_part     VARCHAR(50),
    ADD COLUMN injury_notes         VARCHAR(255);
```

Note: `player.team` already exists, is indexed, and is populated by the current mapper —
do not touch it.

**`V14__seed_nfl_teams.sql`** (reference data only): 32 INSERT rows. For 31 teams
`espn_abbrev = abbrev`; the single divergent row is
`('WAS','WSH','Washington Commanders')`. Canonical abbrev list (from the source audit):
ARI ATL BAL BUF CAR CHI CIN CLE DAL DEN DET GB HOU IND JAX KC LAC LAR LV MIA MIN NE NO
NYG NYJ PHI PIT SEA SF TB TEN WAS. (No OAK.)

### 3.2 Entities

- **`Player`**: add the five fields, nullable `@Column`s matching V13, Lombok per existing
  entity style. No behavior change.
- **`NflTeam`** (new, `app.readoption.<package per repo convention for reference entities>`):
  maps `nfl_team`; `abbrev` is `@Id`; audit timestamps via the existing
  `@PrePersist`/`@PreUpdate` pattern. Plus `NflTeamRepository`.

### 3.3 Mapper extension

Extend the Sleeper player mapper to land: `depth_chart_position`, `depth_chart_order`,
`injury_status`, `injury_body_part`, `injury_notes`. All null-tolerant — a healthy backup
(e.g. player 11435) has all injury fields null and a populated depth chart; free agents may
have everything null. **The source field for team is `team`, not `team_abbr`** (the blob
carries both; `team_abbr` is null) — verify the existing mapping reads `team` and leave it.

### 3.4 Tests (Commit A)

- Mapper unit tests with two blob-shaped fixtures: Mahomes-shape (starter, injured — all
  five fields populated, incl. `injury_notes: "Surgery"`) and Wilson-shape (healthy backup —
  depth fields populated, injury trio null). Assert field-by-field.
- Existing `@DataJpaTest` slices on the Testcontainers pgvector image implicitly validate
  V13/V14 apply cleanly under `ddl-auto=validate` — confirm the suite stays green.

---

## 4. Commit B — schedule sync + bye derivation

### 4.1 Client

**`EspnScheduleClient`** (new; RestClient per repo HTTP idiom; base URL
`https://site.api.espn.com`):

```
GET /apis/site/v2/sports/football/nfl/teams/{espnAbbrev}/schedule?season={year}
```

No `X-Fantasy-Filter`. Map only what the sync needs from the payload:
`events[].seasonType.type`, `events[].week.number`,
`events[].competitions[0].competitors[]` → per competitor: `team.abbreviation`, `homeAway`.

### 4.2 `TeamScheduleSyncService`

For each of the 32 `nfl_team` rows, for the configured season
(`readoption.current-season`):

1. Fetch the team's schedule via its `espn_abbrev`.
2. **Filter `seasonType.type == 2`** (regular season only). This is load-bearing — see
   decision 10.
3. For each remaining event: our competitor row is the one whose `team.abbreviation`
   equals our `espn_abbrev` → take its `homeAway`; the opponent is the other competitor.
4. **Crosswalk the opponent** ESPN→Sleeper via `nfl_team.espn_abbrev` (load the map once,
   not per row). An unknown ESPN abbreviation **throws** and fails that team's sync loudly —
   a vocabulary surprise must never land dirty rows.
5. **Delete-and-reload per `(team, season)`** — the derived-shape rebuild rule, same
   posture as `player_scoring`.
6. **Derive `bye_week`, loudly:** collect the landed week numbers; if **exactly 17 rows
   landed and exactly one week in 1..18 is missing**, that week is the bye — write it to
   `nfl_team.bye_week`. Otherwise write **null** and log WARN with the team and the week
   set. A partial fetch must produce an absent bye, never a wrong one.
7. Three-outcome sync report per repo convention (per-team success / skipped / failed).

### 4.3 Persistence + trigger

- **`TeamSchedule`** entity: composite PK via `@IdClass` (follow `DraftPickId` shape);
  implements `Persistable` with the `@Builder.Default` isNew pattern (17-row bulk insert
  per team — the SELECT-per-entity elimination applies).
- Sync trigger endpoint following the existing sync-controller convention, e.g.
  `POST /api/sync/schedule?season=2026`.

### 4.4 Tests (Commit B)

- **seasonType filter:** a fixture payload containing one preseason (type 1) and one
  regular-season (type 2) event — assert exactly one row lands.
- **Bye derivation:** (a) 17 rows, week 9 missing → bye = 9; (b) 15 rows (partial) →
  bye = null + WARN; the fixture must make the wrong answer *available* so the test can
  prove it wasn't taken.
- **Crosswalk:** a fixture where the opponent is `WSH` → asserts the landed row says `WAS`;
  and an unknown abbrev fixture → asserts the loud throw.
- **homeAway extraction:** our-competitor selection by espn_abbrev match (fixture where our
  team is the *away* competitor, so order-0 assumptions would fail).

---

## 5. Commit C — the agent feels it (view enrichment)

### 5.1 `PlayerProfileView` role block

New fields on the view (populated in `ProfileScoringService`):

| Field | Source | Degradation |
|---|---|---|
| `team` | player.team | null → "free agent / no team" |
| `depthChartPosition` | raw column (e.g. `SWR` stays `SWR`) | null → "role unconfirmed" |
| `depthChartOrder` | raw column | null → "role unconfirmed" |
| `depthChartAhead` | query below | empty list when order = 1; omitted when role unconfirmed |
| `injuryStatus` / `injuryBodyPart` / `injuryNotes` | raw columns | null trio → "no injury reported" |
| `byeWeek` | nfl_team via LEFT JOIN on player.team | no team / unknown team (e.g. OAK) → "bye unknown — team context unavailable" |
| `earlyOpponents` | team_schedule weeks 1–3, e.g. `"W1 vs DEN (home)"` | same degradation as bye |

**`depthChartAhead` query semantics:** same `team`, same **raw** `depth_chart_position`,
`depth_chart_order` strictly lower, ordered by `depth_chart_order`; return full names.
Spring Data derived query or `@Query` on `PlayerRepository` — keep it in SQL, not a Java
filter over a team fetch.

`earlyOpponents` is deliberately fixed at weeks 1–3: the draft is pre-season, so "next
opponents" and "first opponents" coincide. In-season "next from current week" is Phase 5 —
do not build a current-week concept now.

### 5.2 `DraftStateView` roster byes

Each `userRoster` entry gains `byeWeek` (same LEFT JOIN, same degradation string). This is
what lets the agent see shared-bye risk across the user's picks without a new tool.

### 5.3 Tool descriptions (the behavior lever)

Update the `@Tool`/`@ToolParam` description text so the model knows the facts exist:

- `get_player_profile`: now advertises "…including team, depth chart role and the players
  ahead of him, injury status/detail, bye week, and weeks 1–3 opponents."
- `get_draft_state`: now advertises "…roster entries include bye weeks."

Description text only. **No new parameters on any tool.**

### 5.4 Tests (Commit C)

- Profile enrichment fixtures: starter (order 1, empty `depthChartAhead`), backup (names
  ahead, correctly ordered), injured (trio populated), free agent (null team → all
  degradation strings present, row NOT dropped), unknown-team (`OAK` → degradation, no
  silent remap).
- **Sub-position scoping proof:** fixture team with an order-1 LWR, order-1 RWR, and an
  order-2 SWR — the SWR's `depthChartAhead` contains only the order-1 SWR-ladder player
  (add one) and neither the LWR nor the RWR. This test must fail under a
  normalized-WR-scoped query.
- `DraftStateView` bye enrichment.
- **Re-assert the schema-parsing safety test:** the three tools still expose only their
  documented parameters; enriched *responses* are fine, new *parameters* are a failure.
- **Regression anchors must hold byte-identically:** Barkley 208.50 / 226.00 / 243.50,
  Mahomes −2/INT. All 253 existing tests stay green.

---

## 6. Out of scope — do not build

- `get_team_context` tool (graduation-gated on the acceptance transcript). If it ever
  graduates, it wraps the internal `TeamContextService` — the shared name is deliberate
  layering, not an early build of the tool.
- Numeric strength-of-schedule / defensive grades (no honest data basis yet; 4.5+).
- News ingestion (Phase 4.4, vector RAG).
- Any current-week / in-season schedule concept (Phase 5).
- Enum normalization of `depth_chart_position` or `injury_status` (graduation trigger:
  the first time *Java* logic must branch on one of these values).
- Any change to the agent loop, tool count, or tool parameters.

## 7. Acceptance (owner: Sorin — not part of this build)

Post-build runbook, run by hand against live sessions: data-quality coverage queries,
re-run of the 4.2 starvation prompts, three assertions (advice cites previously-disclaimed
facts; VORP/ADP still verbatim from the board; null-depth players degrade to "role
unconfirmed", not a guessed role).

---

## 8. As-built notes (Claude Code, 2026-07-06)

Deviations and additions, each with reasoning per CLAUDE.md:

1. **`SMALLINT` columns map via `@JdbcTypeCode(SqlTypes.SMALLINT)` on `Integer`
   fields** (`player.depth_chart_order`, `nfl_team.bye_week`, `team_schedule.season/week`).
   The spec's DDL is kept verbatim; under `ddl-auto=validate` a plain `Integer`
   property expects `integer` and fails startup against `smallint`.
2. **The delete in delete-and-reload is a bulk JPQL `@Modifying(flushAutomatically,
   clearAutomatically)` statement**, not a derived `deleteBy`. A re-sync inserts rows
   with the *same* composite PKs; Hibernate flushes INSERTs before entity DELETEs, and
   a bulk statement bypasses the persistence context (stale managed rows collide with
   the reload). Both failure modes are pinned by `TeamScheduleRepositoryTest` — the
   second was caught live by that test during the build.
3. **`TeamContextService` (new, `app.readoption.team`) is the single home of the
   degradation vocabulary** and the player→team context reads. Both enriched views
   (§5.1, §5.2) speak its constants, so the profile and the roster can never drift
   apart on the strings the acceptance runbook greps for.
4. **Two extra degradation strings beyond §5.1's table**, because "team context
   unavailable" would be a lie for a known team that simply hasn't synced:
   known team + null bye → `"bye unknown"`; known team + no schedule rows →
   `"unknown - schedule not synced"`. No-team/unknown-team keep the spec's strings.
5. **The schedule sync's `skipped` outcome = zero regular-season events** (season not
   published). A skip never deletes — last season's context survives a too-early sync.
6. **Sync endpoint is `POST /api/teams/schedule/sync?season=`** (season optional,
   defaults to `readoption.current-season`) — the repo's resource-scoped controller
   convention (`/api/players/sync`, `/api/projections/sync/espn`), not the spec's
   illustrative `/api/sync/schedule`.
7. **`injury_notes`/`injury_body_part` are truncated to their column bounds at the
   mapper** — free-text source fields must not fail a whole sync batch on length.
8. **`PlayerProfileView` is `@JsonInclude(NON_NULL)`** so §5.1's "omitted" degradations
   are truly omitted from the tool result rather than serialized as `null`.
9. **All 253 existing tests pass unchanged, plus 32 new (285 total).** Anchors
   verified byte-identical (208.50 / 226.00 / 243.50; Mahomes −2/INT).

Post-review additions (2026-07-07, owner's findings checklist):

10. **`injury_status` is the authoritative injury flag at the read boundary** (F1):
    body part and notes serialize only alongside a non-null status; a null status
    reads "no injury reported" with the attributes suppressed. Sleeper's current blob
    never carries notes without a status (verified against the full payload that day),
    but that invariant is the vendor's — the gate plus a counterfactual fixture pin
    our behavior for the day it breaks.
11. **`EspnScheduleClient` constructor-injects Boot's `RestClient.Builder`** (F2).
    Note: `EspnClient` (committed, Phase 2) also uses `RestClient.create()` — migrate
    only when explicitly asked, same posture as `SleeperClient`.
12. **`espn_id` carry-over in the player upsert** (F4, own commit): the Sleeper blob
    doesn't carry `espn_id`; the id-mapping stage does. The upsert now copies the
    existing value forward so a plain `/api/players/sync` can't null it. Regression
    test: `espnIdSurvivesPlainSync`. Suite is now 287.
