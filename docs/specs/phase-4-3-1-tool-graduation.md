# Phase 4.3.1 Spec — Tool-Surface Graduation (find_player + get_team_context)

**Status:** ready to build
**Depends on:** Phase 4.3 complete (commits A–D). Migration head: V14. Tests: 287+ green.
**Reader:** Claude Code, working in the read-option repo with CLAUDE.md conventions loaded.

---

## 1. Context and goal

Two tools graduate from the deferred list, each on transcript evidence per the graduation
rule — the requirements were written by observed agent failures, not by design taste:

- **F7 (fabrication from a truncated view):** asked about Brandon Aiyuk (available, below
  the board slice), the agent asserted "he's already been taken." Asked about Brandin
  Cooks, it concluded "not an option regardless." Root cause: `get_draft_board` returns a
  top-N VORP slice, nothing tells the model the view is truncated, and no tool resolves a
  player *name* — so absence from the slice was misread as absence from the league.
  → **`find_player`**, plus a truncation warning in the board tool's description.
- **Team-room gap (the get_team_context hearing):** asked "who's in the 49ers backfield
  besides McCaffrey," the agent said, verbatim: "my tools only surface depth chart details
  through individual player profiles … not necessarily the full backfield picture behind
  him. I don't have a way to pull his handcuff's profile without knowing that player's ID."
  → **`get_team_context`**, wrapping the existing `TeamContextService` + a depth-chart
  room query.

Also in scope: **F8** (the model expanded raw `SWR` into the wrong English — "split wide
receiver" instead of slot) → a vocabulary legend in description text.

**Additionally, 4.3's acceptance assertion (c) is blocked on this increment:** the profile
degradation path (free agents → "context unavailable") is unreachable through the agent
because playerIds only come from the board slice. `find_player` opens that door; the Cooks
prompt becomes the acceptance test.

---

## 2. Decisions record (settled — deviations require reasoning per CLAUDE.md)

1. **Two new tools, total five.** Both read-only, both on the per-request
   `DraftAgentTools` POJO. `sessionId`/`scoringRules` remain constructor fields, never
   `@ToolParam` — the schema-parsing safety test widens to five tools and stays the
   enforcement.
2. **`find_player` searches by name, returns candidates, never guesses.** Partial,
   case-insensitive match on `player.full_name`, active players only, capped at 5
   candidates. Ambiguity returns multiple candidates; no match returns an empty list —
   the tool result says so plainly. Drafted status is computed against THIS session's
   picks (the bound sessionId), with the taking pick number when drafted.
3. **`get_team_context` takes a normalized position filter; the vocabulary graduation
   trigger has fired — minimally.** The 4.3 rule said the raw depth vocabulary gets
   Java-side normalization "the first time Java logic must branch on one of these
   values." Mapping the filter `WR → {LWR, RWR, SWR}` for the room query IS that branch.
   Graduate with a small one-way map at the read boundary (normalized position → set of
   raw ladder labels), NOT an enum on the landing column — the column stays raw. The map
   lives beside the query it serves.
4. **Unknown team abbrev in `get_team_context` degrades loudly in the tool RESULT**
   ("unknown team 'XYZ' — no context available"), never throws to a 500: a model-supplied
   bad argument is the error-tool-response self-correction path (4.2 precedent: unknown
   position throws inside the tool and the manager converts it — either shape is
   acceptable, but the result must name the bad input).
5. **Description text is the first-line fix for both F7 and F8** (the behavior lever):
   board truncation warning, find_player cross-reference, and the raw-vocabulary legend.
6. **No agent-loop, memory, or prompt-template changes.** The loop is untouched; only the
   tools object and descriptions grow.

---

## 3. Commit E — `find_player`

### 3.1 Repository

`PlayerRepository`:

```java
List<Player> findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc(String name);
```

(Derived query is fine; ILIKE via `@Query` acceptable if the derived name is judged
unreadable — deviation note either way.)

### 3.2 Tool

On `DraftAgentTools`:

```java
@Tool(description = "Find a player by (partial) name. Use this when a player is not "
        + "visible on the draft board — the board shows only the top N by VORP, so "
        + "absence from the board does NOT mean a player is drafted or out of the "
        + "league. Returns up to 5 candidates with playerId, position, team, whether "
        + "they are drafted in THIS session (and by which pick), and whether they "
        + "have a current-season projection. An empty result means no active player "
        + "matches the name.")
public List<PlayerSearchResult> findPlayer(
        @ToolParam(description = "Full or partial player name, case-insensitive")
        String name) { ... }
```

Blank/null name → throw `IllegalArgumentException` (error tool-response, 4.2 unknown-
position precedent). Entry/exit DEBUG logs with the RAW model-supplied arg, per the 4.2
both-ends logging convention.

### 3.3 View

`PlayerSearchResult` record (agent package):

| Field | Semantics |
|---|---|
| `playerId` | for follow-up `get_player_profile` calls |
| `fullName`, `position` | identity |
| `team` | raw, or the `TeamContextService.NO_TEAM` label when null |
| `drafted` | boolean, against THIS session's picks |
| `takenAtPick` | Integer, null unless drafted |
| `hasProjection` | boolean — current-season mart row exists (i.e. board-eligible before draft status) |

Drafted lookup: one query over the session's picks for the candidate ids — not one query
per candidate. `@JsonInclude(NON_NULL)` for `takenAtPick`.

### 3.4 Tests (Commit E)

- Repository test (real container): partial match, case-insensitivity, cap at 5, active
  filter.
- Tools test: delegation with bound sessionId; blank-name throw; drafted flag + taking
  pick for a drafted fixture; NO_TEAM label for a free-agent fixture.
- **Schema-parsing safety test widened:** five tools, `find_player` exposes ONLY `name`.
- **Aiyuk-shaped service-level regression:** a player who is active, has a projection,
  is NOT drafted, and would fall below a board slice — `findPlayer` returns him with
  `drafted=false, hasProjection=true`. (The model-behavior half is acceptance, §6.)

---

## 4. Commit F — `get_team_context`

### 4.1 The position map (the graduation trigger, minimal form)

In the team package, beside the query it serves:

```java
/**
 * Normalized fantasy position -> the raw depth-chart ladders that compose it.
 * This is the vocabulary graduation trigger firing (4.3 decision 2): the first
 * Java branch on the raw vocabulary. A one-way read-boundary map — the landing
 * column stays raw; no enum.
 */
static final Map<String, Set<String>> POSITION_LADDERS = Map.of(
        "QB", Set.of("QB"),
        "RB", Set.of("RB"),
        "WR", Set.of("LWR", "RWR", "SWR"),
        "TE", Set.of("TE"));
```

Unknown filter value → the loud-bad-argument path (decision 4).

### 4.2 Service + repository

`TeamContextService` gains `teamRoom(String team, String position, int season)`:
`nfl_team` lookup (existing degradation vocabulary on miss), the bye + early opponents it
already builds, plus the room — players on `team` whose `depth_chart_position` is in the
ladder set, ordered by ladder then order:

```java
// PlayerRepository
List<Player> findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
        String team, Set<String> depthChartPositions);
```

Room entries carry: `fullName`, raw `depthChartPosition`, `depthChartOrder`,
`injuryStatus` (status-gated per F1 — reuse the profile's rule, do not reimplement it),
and `drafted` in THIS session (the handcuff question is only useful if availability is
visible). Null position filter = all four fantasy ladders.

### 4.3 Tool

```java
@Tool(description = "Get one NFL team's context: bye week, weeks 1-3 opponents, and the "
        + "depth chart room at a position — every player on that team's ladder(s) with "
        + "their order, injury status, and whether they are already drafted in this "
        + "session. Use for handcuff questions, backfield/receiver-room composition, "
        + "and role competition. Positions: QB, RB, WR, TE (WR spans the LWR/RWR/SWR "
        + "ladders: LWR/RWR are outside receivers, SWR is the slot). Team is the "
        + "standard abbreviation (SF, KC, WAS...).")
public TeamRoomView getTeamContext(
        @ToolParam(description = "Team abbreviation, e.g. SF") String team,
        @ToolParam(description = "Optional position filter: QB, RB, WR, or TE", required = false)
        String position) { ... }
```

### 4.4 Tests (Commit F)

- Position-map completeness: every ladder label in the map exists in the audited
  vocabulary; WR spans exactly the three receiver ladders.
- Room query (real container): the CIN receiver-room fixture from
  `PlayerDepthChartQueryTest` reused — `WR` filter returns LWR+RWR+SWR players in
  ladder-then-order sequence; `RB` filter excludes them.
- Service test: unknown team → degradation labels; drafted flags batch-computed.
- Tools test: bound sessionId; unknown position → loud bad-argument path.
- **Schema-parsing safety test:** `get_team_context` exposes ONLY `team` + `position`.

---

## 5. Commit G — description patches (F7 + F8 residue)

- `get_draft_board`: append "Returns only the top N available players by VORP — a player
  absent from this list is NOT necessarily drafted; use find_player to check a specific
  player by name."
- `get_player_profile`: append the vocabulary legend "Depth positions are raw source
  labels: LWR/RWR are outside receivers, SWR is the slot receiver."
- Description text only; no parameter changes anywhere. May be folded into E/F if
  splitting is awkward — deviation note if so.

---

## 6. Out of scope

- Board pagination or a larger slice (find_player is the targeted fix).
- Enum normalization of the landing columns (the map in §4.1 is the whole graduation).
- Staleness guard ("re-check state before asserting availability" — the iterations:0
  observation): parked, system-prompt fix if it ever bites, not code.
- Any mutation tool; any memory/prompt-template change.

## 7. Acceptance (owner: Sorin)

Live session, four prompts:
1. The Aiyuk prompt verbatim → expect "available, ranked below the current board view"
   (or a find_player-grounded equivalent), never "he's been taken."
2. The Cooks prompt verbatim → find_player resolves him; if he is a free agent, the
   profile shows the degradation vocabulary — **this closes 4.3 assertion (c)**.
3. The 49ers-backfield prompt verbatim → the room enumerated with orders and draft
   status; handcuff identified by name.
4. One ASB-style profile prompt → "slot" language (F8 legend landed), no invented
   expansions.
Standing assertions: VORP/ADP verbatim vs the board endpoint; schema test proves five
tools with exactly their documented parameters.

---

## 8. As-built notes (Claude Code, 2026-07-07)

Deviations and additions, each with reasoning per CLAUDE.md:

1. **Tool names are the Java method names** (`findPlayer`, `getTeamContext`) — the
   4.2 convention (`getDraftState` etc.); the spec's snake_case names are prose
   aliases, not the wire names.
2. **Commit G folded per §5's escape hatch:** the board truncation warning (it
   cross-references `findPlayer`) rides Commit E; the profile vocabulary legend
   rides Commit F. No standalone Commit G.
3. **Session-bound drafted flags are computed at the tool layer**
   (`DraftAgentTools.toRoomView`), not inside `TeamContextService.teamRoom` — §4.2's
   signature carries no session, and pulling `DraftPickRepository` into the team
   package would point a reference-data package at the draft domain. The batch
   In-query requirement holds (one `findBySessionIdAndPlayerIdIn` per tool call).
4. **Room entries carry `playerId`** (absent from §4.2's field list): the observed
   failure was verbatim "I don't have a way to pull his handcuff's profile without
   knowing that player's ID" — a room of names without ids would rebuild the same
   gap one hop later.
5. **The F1 injury rule's single home is `ProfileScoringService.injuryLabel`**,
   called by both the profile and the room entries — reuse per §4.2, extracted
   rather than duplicated.
6. **`K`/`DEF` position filters throw from the ladder map** (they parse as valid
   `Position` constants but have no depth ladder); garbage like `PUNTER` throws
   from the parse. Both land on the same error-tool-response path, message naming
   the valid filters.
7. **Blank `team` throws (bad argument); unknown team degrades in the RESULT** via
   a `note` naming the input, remaining fields omitted by `NON_NULL` (decision 4).
   Team input is trimmed/uppercased before lookup — the model says "sf", the
   crosswalk speaks "SF".
8. **`find_player`'s empty result short-circuits** — no drafted/projection batch
   queries fire for zero candidates.
