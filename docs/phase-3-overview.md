# Phase 3 — Natural-Language League Customization: Walkthrough

This document describes what is actually built in `app.readoption.customization`
(and `.validation`) as of the Phase 3 Commit-3 work. It is a walkthrough of the
code, not a restatement of the spec; where the code and `docs/specs/phase-3.md`
diverge, the code is described and the divergence is flagged in
[Drift from spec](#8-drift-from-spec).

---

## 1. What Phase 3 does

A user describes their league and draft style in plain English ("12-team
half-PPR, TE premium, I never draft a QB before round 10"). An LLM parses that
description into a structured, validated spec; a deterministic resolver turns
the spec into the engine's scoring/roster config; the engine consumes it. The
LLM translates intent to structure — it selects presets, sets flags, and copies
numbers the user stated. It never originates a scoring number: the parse-target
types give it no field to write one into.

## 2. The three-type boundary

The phase's safety boundary is a deliberate three-type split. Do not collapse it.

```
ParsedLeague  ──►  LeagueRulesResolver  ──►  LeagueRules
(LLM output:       (deterministic,           (engine input:
 spec — preset,     no LLM; the              materialized ScoringRules
 flags, extracted   flag→number registry     + LeagueSettings
 numbers)           lives here)              + PlayoffFormat)
```

- **`ParsedLeague`** is the model's output type — the root of one
  `BeanOutputConverter<ParsedLeague>` call. It is a narrow *spec*. Its
  `ScoringSpec` has exactly three kinds of field:
  - **PRESET** — `basePreset` (`ReceptionFormat`: `STANDARD` / `HALF_PPR` /
    `PPR`), a closed enum with **no safe default**: unstated reception scoring
    parses to `null` and raises a BLOCKING issue rather than a guess.
  - **EXTRACTED** — `passingTdPoints`, `interceptionPoints` (`BigDecimal`,
    bounded by `@DecimalMin`/`@DecimalMax`): the model may only copy these out
    of the user's text; `null` means "not stated, resolve the default."
  - **FLAG** — `tePremium` (`boolean`): the model states that the rule exists;
    the value it resolves to is not the model's to set.
- **`LeagueRulesResolver`** maps the spec to the domain deterministically —
  `@Component`, zero LLM involvement, unit-testable without a model. The
  flag→number registry lives here and only here:
  - `LeagueRulesResolver.TE_PREMIUM_BONUS = new BigDecimal("0.5")` — what the
    TE-premium flag is worth (extra points per TE reception on top of the base
    reception value). This number exists nowhere in the parse targets.
  - `LeagueRulesResolver.DEFAULT_PASSING_TD_POINTS = new BigDecimal("4")` —
    applied when the spec's `passingTdPoints` is null.
  - The interception default comes from the engine's own constant,
    `ScoringRules.DEFAULT_INTERCEPTION_POINTS` (`"-2"`), and the no-premium TE
    bonus from `ScoringRules.NO_TE_BONUS` (`"0"`).
- **`LeagueRules`** is the resolved domain object: fully materialized
  `ScoringRules` (every multiplier a `BigDecimal`, nothing left to infer),
  roster `LeagueSettings`, and a nullable `PlayoffFormat`. Only the resolver
  constructs it; the LLM never emits it.

Stated plainly: **the model has no field to write a scoring number into.** It
can say "TE premium," but `0.5` is a constant in the resolver. The system
prompt (`prompts/league-parser.txt`) reinforces the same contract in prose
("the premium's numeric value is not yours to set and has no field"), but the
enforcement is the type shape, not the prompt.

The parse also carries a second partition, `DraftTactics` — the strategy-bound
half (positional strategy, risk posture, earliest-round-by-position map,
free-form notes). It is deliberately asymmetric to `LeagueRulesSpec`: tactics
have a free-text tail (`freeformNotes`) because their consumer is the Phase 4
draft agent (an LLM that can reason over prose); the rules spec has none,
because a deterministic engine consumes it and prose is useless to it.

## 3. End-to-end flow

The flow is **parse → refine → confirm**, orchestrated by `LeagueConfigService`
behind `LeagueConfigController` (`/api/league`). Two properties define the
whole design:

- **Stateless.** There is no `ChatMemory` and no session store. The partial
  `ParsedLeague` rides in the payloads: each `ParseResult` returns it, and the
  client carries it back in the next `RefineRequest`/`ConfirmRequest` body. The
  conversation state is a typed object, not a chat session. The refine turn
  counter is likewise client-carried (`RefineRequest.turn`).
- **Phased READ → REASON → WRITE.** `/parse` and `/refine` are the REASON phase
  and each makes a **live Anthropic call** (via `LeagueParsingService`), with no
  transaction open. `/confirm` is the WRITE phase — `@Transactional`, the only
  writer in the flow, and it contains **no model call**. `READY` is not a
  commit; nothing persists before confirm.

The sequence:

1. **`POST /api/league/parse`** — `LeagueParsingService.parse(description)`
   calls the model (`ChatClient` + `BeanOutputConverter<ParsedLeague>`, model
   id from `CustomizationProperties`, system prompt loaded fail-fast at startup
   from `classpath:prompts/league-parser.txt`). Structured output is
   best-effort: any malformed/out-of-enum output surfaces as
   `LeagueParseException`, which the service maps to a single BLOCKING `parse`
   issue with a `null` parsed object — never a silent default.
2. **Validation** (`LeagueConfigService.validate`) runs in two passes merged
   into one issue list:
   - the **Jakarta annotation pass**, run programmatically (the object came
     from the model, not from a client we can 400). Violations under `rules.*`
     are BLOCKING; violations under `tactics.*` are demoted to ASSUMPTION
     (tactics are soft-validated — their consumer is an LLM).
   - the **object-level validators**: `LeagueRulesValidator` for the
     engine-bound half (null `basePreset` BLOCKING; `playoffTeams >
     teamCount` BLOCKING; playoff end-before-start BLOCKING; flex eligibility
     outside RB/WR/TE BLOCKING; null `passingTdPoints` ASSUMPTION) and
     `DraftTacticsValidator` for the strategy-bound half (the one tactics field
     with a mechanical Phase 4 consumer, `earliestRoundByPosition`, gets a
     BLOCKING 1..30 bound with a value-bearing message).
   - **Merge rule:** where both passes flag the same field, the object
     validator's value-bearing message wins (e.g. a null `basePreset` reports
     "no safe default — is the league standard, half-PPR, or full PPR?", not
     the bare `@NotNull` message). Messages are value-bearing by contract —
     they feed the repair prompt and the user.
3. **Status derivation** (`ParseResult.of`): any BLOCKING issue ⇒
   `NEEDS_INPUT`; otherwise `READY`. ASSUMPTION issues do not block.
4. **`POST /api/league/refine`** — one repair turn. The service first enforces
   the turn cap (`CustomizationProperties.maxRefineTurns`, configured 5): past
   it, **no model call** — the current object comes back with a BLOCKING
   `refine` issue. Otherwise `LeagueParsingService.refine` sends the current
   object serialized as JSON plus the correction ("change only what the
   correction addresses; preserve every other field exactly"). A failed refine
   keeps the prior object — a broken repair turn must not lose state.
5. **Drift guard** — after a successful refine, `RefineDriftGuard.diff` compares
   the prior and returned objects field by field, deterministically. Every
   changed field is surfaced as an ASSUMPTION issue naming both values
   ("Changed during refine: 12 -> 10 …"): the intended change reads as
   confirmation, anything else reads as drift for the user to reject. ASSUMPTION
   (not BLOCKING) so drift cannot dead-lock the repair loop. `BigDecimal` fields
   compare by `compareTo`, so a model re-emitting `4` as `4.0` is not drift.
6. **`POST /api/league/confirm`** — the commit gate and the flow's **only
   writer**. It re-validates from scratch (a prior `READY` proves nothing about
   the payload just handed over); any BLOCKING issue ⇒
   `LeagueConfigNotReadyException` ⇒ **409** ProblemDetail carrying the issue
   list, no write. Otherwise it runs `LeagueRulesResolver.resolve`, maps the
   **resolved** values (not the raw spec) onto a `LeagueConfig` entity, and
   saves. The transaction never spans an LLM call. The response is the persisted
   entity, including its generated `id`.

## 4. Class map

| Class | Responsibility |
|---|---|
| **Parse targets (the LLM's output types)** | |
| `ParsedLeague` | Root output of one `BeanOutputConverter` call: `rules` (engine-bound, hard-validated) + nullable `tactics` (strategy-bound, soft-validated). |
| `LeagueRulesSpec` | Engine-bound half: `scoring` + `roster` + nullable `playoff`; `@Valid` cascades into the nested records. |
| `ScoringSpec` | PRESET (`basePreset`) + EXTRACTED (`passingTdPoints`, `interceptionPoints`, both nullable `BigDecimal`) + FLAG (`tePremium`). |
| `RosterSpec` | Pure extraction: team count, positional slots, flex eligibility, superflex, bench. |
| `PlayoffSpec` | Playoff teams/start/end weeks; captured now, consumed in Phase 4; cross-field rules live in the validator. |
| `DraftTactics` | Strategy half: `positionalStrategy`, `riskPosture`, `earliestRoundByPosition`, `freeformNotes` (open tactic tail). |
| `PositionalStrategy`, `RiskPosture` | Closed-enum draft leans; `null` means the user stated none. |
| **Resolver + domain** | |
| `LeagueRulesResolver` | Deterministic spec→domain mapper; owns the flag→number registry (`TE_PREMIUM_BONUS`, `DEFAULT_PASSING_TD_POINTS`); no LLM. |
| `LeagueRules` | Resolved domain object: `ScoringRules` + `LeagueSettings` + nullable `PlayoffFormat`; only the resolver constructs it. |
| `PlayoffFormat` | Resolved playoff structure, mapped straight through from `PlayoffSpec`. |
| **Validators** (`.validation`) | |
| `LeagueRulesValidator` | Object-level cross-field rules on the engine-bound half; collects all issues (never first-fail); value-bearing messages. |
| `DraftTacticsValidator` | BLOCKING 1..30 bound on `earliestRoundByPosition`; null-safe on null tactics and null map. |
| `ValidationIssue` | One finding: dotted `field`, `severity`, value-bearing `message`. |
| `IssueSeverity` | `BLOCKING` (must resolve) vs `ASSUMPTION` (surfaced, may proceed). |
| **Drift guard** | |
| `RefineDriftGuard` | Deterministic field-by-field diff of before/after refine; every change → ASSUMPTION issue naming both values; `BigDecimal` by `compareTo`. |
| **Service + controller** | |
| `LeagueConfigService` | Orchestrates parse/refine/confirm; merges the two validation passes; enforces the refine cap; confirm is the only (transactional) writer. |
| `LeagueParsingService` | `ChatClient` + `BeanOutputConverter<ParsedLeague>` wrapper; separable `convert()` seam; all failures → `LeagueParseException`. |
| `LeagueConfigController` | `POST /api/league/{parse,refine,confirm}`; stateless; bean-validates the request wrappers only. |
| `ParseRequest` / `RefineRequest` / `ConfirmRequest` | Request bodies. `current` in refine/confirm is deliberately not cascade-validated at the HTTP boundary — mid-repair it is expected to be partial. |
| `ParseResult` / `Status` | One turn's outcome: parsed object + issues + derived status (any BLOCKING ⇒ `NEEDS_INPUT`, else `READY`). |
| `LeagueParseException` | Any model-call/conversion failure; mapped to a BLOCKING `parse` issue. |
| `LeagueConfigNotReadyException` | Confirm called with BLOCKING issues remaining; mapped to 409 + the issue list by `GlobalExceptionHandler`. |
| **Persistence** | |
| `LeagueConfig` | The confirmed-config entity — typed scoring/roster/playoff columns + JSONB tactics; `IDENTITY` id; insert-only (deliberately not `Persistable`). |
| `LeagueConfigRepository` | Plain `JpaRepository<LeagueConfig, Long>`. |
| `FlexEligibleConverter` | `Set<Position>` ⇄ sorted CSV of enum names for the `flex_eligible` varchar column. |
| **Config** | |
| `CustomizationProperties` | `@Validated` `readoption.customization.*`: `model` (`claude-sonnet-4-6`) + `maxRefineTurns` (5). The system prompt is not here — it lives in `prompts/league-parser.txt`. |

## 5. What persists

`V10__create_league_config_table.sql` creates `league_config`, written only by
the confirm gate — one row per confirmed config, insert-only, no FK (there is
no user table yet).

- **Resolved scoring as typed columns** — engine-consumed, so `NUMERIC` gives
  validate-on-write and queryability: `reception_format VARCHAR(20)` (enum
  *name*, never ordinal), `passing_td_points`, `interception_points`,
  `te_reception_bonus`, all `NUMERIC(4,2) NOT NULL`. Every number in these
  columns came from the deterministic resolver — the LLM has no path to
  originate one. `te_reception_bonus` is `0` when no TE premium.
- **Roster columns** — `team_count`, `qb_slots`, `rb_slots`, `wr_slots`,
  `te_slots`, `flex_slots`, `flex_eligible` (sorted CSV of `Position` names,
  e.g. `RB,TE,WR`, via `FlexEligibleConverter`), `superflex_slots`,
  `bench_slots`, all `NOT NULL`.
- **Nullable playoff columns** — `playoff_teams`, `playoff_start_week`,
  `playoff_end_week`; populated only when the user stated playoffs.
- **`tactics JSONB`** — the typed `DraftTactics` via
  `@JdbcTypeCode(SqlTypes.JSON)` (same idiom as `source_payload`); nullable
  (a tactics-free league stores `NULL`). LLM-consumed, never queried.
- Audit `created_at` / `updated_at` via `@PrePersist`/`@PreUpdate`.

**Consumed now vs captured-but-inert:** the resolved scoring columns are the
part the engine consumes today (they materialize a `ScoringRules`). The rest is
captured now and inert until Phase 4: the playoff columns feed playoff
strength-of-schedule, the roster shape feeds replacement-level/VORP math, and
`tactics` feeds the draft agent.

### Relationship to `player_scoring` (Phase 4 design note)

`player_scoring` already holds scored points per `(player_id, year,
scoring_format)` — including 2026 rows scored from projections — but only for
the six `ScoringFormat` presets, all of which bake in the engine defaults
(−2/INT, no TE bonus; see `ScoringFormat.toScoringRules()`). A confirmed league
config coincides with one of those presets exactly when its resolved rules land
on `passingTdPoints ∈ {4, 6}`, `interceptionPoints = -2`, and no TE premium —
which, since those are also the resolver's defaults, covers the common case.

The sensible Phase 4 consumer shape is therefore a **preset short-circuit**:
check whether the config's resolved `ScoringRules` equals one of the six
`ScoringFormat.toScoringRules()` values (comparing `BigDecimal`s by
`compareTo`, never `equals`) — if so, read the precomputed `player_scoring`
rows; otherwise re-score on demand from the same underlying projection rows
with the custom rules (`ScoringService.calculate` takes arbitrary
`ScoringRules` since the Commit-1 decoupling; in-memory re-scoring of a
season's projections is cheap). Custom-league scores should **not** be written
into `player_scoring` — its key is the closed format enum, one row per named
format; if caching ever proves necessary it belongs in a separate table keyed
by `league_config.id`. For market values, ADP only exists in three buckets
(`ScoringFormat.adpBucket`), so even a fully custom league maps to its nearest
bucket via the config's `reception_format` column. None of this is built in
Phase 3; it belongs to the Phase 4 spec.

## 6. How to exercise it

**Prerequisites:**

- Postgres up: `docker compose up -d` (host port **5433** — WSL occupies 5432).
- `ANTHROPIC_API_KEY` set in the app's environment — `/parse` and `/refine`
  make a live Anthropic call (`claude-sonnet-4-6` per
  `readoption.customization.model`). `/confirm` does not call the model.
- App running on `http://localhost:8080` (Flyway applies V10 on startup).

The examples are bash `curl`; on PowerShell use `curl.exe` with the same
arguments (quote the JSON carefully) or put each body in a file and pass
`--data @body.json`.

### 6a. Parse — a clean description → `200`, `READY`

```bash
curl -s -X POST http://localhost:8080/api/league/parse \
  -H 'Content-Type: application/json' \
  -d '{
    "description": "12-team half-PPR, 6-point passing TDs, TE premium, superflex, 6 teams make the playoffs weeks 15-17, I punt RB early and don'\''t draft a QB before round 10"
  }'
```

Expected `200` with a `ParseResult`. Shape (model output varies in detail, the
structure does not):

```json
{
  "parsed": {
    "rules": {
      "scoring": { "basePreset": "HALF_PPR", "passingTdPoints": 6, "interceptionPoints": null, "tePremium": true },
      "roster": { "teamCount": 12, "qbSlots": 1, "rbSlots": 2, "wrSlots": 2, "teSlots": 1, "flexSlots": 1,
                  "flexEligible": ["RB", "WR", "TE"], "superflexSlots": 1, "benchSlots": 6 },
      "playoff": { "playoffTeams": 6, "playoffStartWeek": 15, "playoffEndWeek": 17 }
    },
    "tactics": {
      "positionalStrategy": "ZERO_RB",
      "riskPosture": null,
      "earliestRoundByPosition": { "QB": 10 },
      "freeformNotes": []
    }
  },
  "issues": [],
  "status": "READY"
}
```

Note `tePremium` is only `true` — there is no bonus-amount field anywhere in
`parsed`. The `0.5` appears for the first time after confirm, in the DB row.
(If the model leaves a field like `interceptionPoints` unstated it stays
`null`; a null `passingTdPoints` would additionally surface as an ASSUMPTION
issue while keeping `READY`.)

### 6b. Parse — reception scoring omitted → `200`, `NEEDS_INPUT`

```bash
curl -s -X POST http://localhost:8080/api/league/parse \
  -H 'Content-Type: application/json' \
  -d '{ "description": "10-team league, 1 QB, 2 RB, 2 WR, 1 TE, 1 flex, 5 bench" }'
```

Expected `200`, `status: "NEEDS_INPUT"`, `parsed.rules.scoring.basePreset`
null, and the value-bearing BLOCKING issue from `LeagueRulesValidator` (its
message wins over the bare `@NotNull` violation):

```json
{
  "issues": [
    { "field": "scoring.basePreset", "severity": "BLOCKING",
      "message": "Reception scoring was not stated and has no safe default — is the league standard, half-PPR, or full PPR?" }
  ],
  "status": "NEEDS_INPUT"
}
```

### 6c. Parse — a contradiction → `200`, `NEEDS_INPUT`

```bash
curl -s -X POST http://localhost:8080/api/league/parse \
  -H 'Content-Type: application/json' \
  -d '{ "description": "6-team PPR league, 8 of the 6 teams make the playoffs in weeks 15 to 17" }'
```

The prompt instructs the model to report contradictions as stated, never to
resolve them; the validator catches it. Expected `200`, `NEEDS_INPUT`, with:

```json
{ "field": "playoff.playoffTeams", "severity": "BLOCKING",
  "message": "Playoff teams (8) cannot exceed league size (6)." }
```

### 6d. Refine — the stateless carry

Take the entire `parsed` object from a prior response and put it in the
`RefineRequest` as `current`, with the correction and the 1-based turn counter
(the client carries the counter because the server keeps no session):

```bash
curl -s -X POST http://localhost:8080/api/league/refine \
  -H 'Content-Type: application/json' \
  -d '{
    "current": {
      "rules": {
        "scoring": { "basePreset": "HALF_PPR", "passingTdPoints": 6, "interceptionPoints": null, "tePremium": true },
        "roster": { "teamCount": 12, "qbSlots": 1, "rbSlots": 2, "wrSlots": 2, "teSlots": 1, "flexSlots": 1,
                    "flexEligible": ["RB", "WR", "TE"], "superflexSlots": 1, "benchSlots": 6 },
        "playoff": { "playoffTeams": 6, "playoffStartWeek": 15, "playoffEndWeek": 17 }
      },
      "tactics": { "positionalStrategy": "ZERO_RB", "riskPosture": null,
                   "earliestRoundByPosition": { "QB": 10 }, "freeformNotes": [] }
    },
    "correction": "actually make it full PPR",
    "turn": 1
  }'
```

Expected `200` with a new `ParseResult`: `parsed.rules.scoring.basePreset` is
now `"PPR"`, and the drift guard reports every changed field as an ASSUMPTION —
the intended change shows up as confirmation:

```json
{ "field": "scoring.basePreset", "severity": "ASSUMPTION",
  "message": "Changed during refine: HALF_PPR -> PPR. If your correction did not ask for this, reject it with another correction." }
```

Any *other* field in that list is drift to reject with another correction.
The cap: with `readoption.customization.max-refine-turns=5`, a request with
`"turn": 6` makes **no model call** and returns `NEEDS_INPUT` with a BLOCKING
`refine` issue telling the user to state the remaining details in a fresh parse.

### 6e. Confirm → `200` with the generated id

Carry the (READY) parsed object into `ConfirmRequest.current` — same shape as
above:

```bash
curl -s -X POST http://localhost:8080/api/league/confirm \
  -H 'Content-Type: application/json' \
  -d '{ "current": { ...the parsed object, as in 6d... } }'
```

Expected `200` with the persisted `LeagueConfig` — note the resolved numbers,
which the request body never contained:

```json
{
  "id": 1,
  "receptionFormat": "HALF_PPR",
  "passingTdPoints": 6.00,
  "interceptionPoints": -2.00,
  "teReceptionBonus": 0.50,
  "teamCount": 12, "qbSlots": 1, "rbSlots": 2, "wrSlots": 2, "teSlots": 1,
  "flexSlots": 1, "flexEligible": ["RB", "WR", "TE"], "superflexSlots": 1, "benchSlots": 6,
  "playoffTeams": 6, "playoffStartWeek": 15, "playoffEndWeek": 17,
  "tactics": { "positionalStrategy": "ZERO_RB", "riskPosture": null,
               "earliestRoundByPosition": { "QB": 10 }, "freeformNotes": [] },
  "createdAt": "2026-07-03T12:00:00", "updatedAt": "2026-07-03T12:00:00"
}
```

`interceptionPoints: -2.00` and `teReceptionBonus: 0.50` are the resolver's
registry values; the spec carried only `interceptionPoints: null` and
`tePremium: true`.

### 6f. Confirm with a BLOCKING issue remaining → `409`

Send a `current` that still has a BLOCKING problem (e.g. `basePreset: null`,
or `"earliestRoundByPosition": { "QB": 40 }`):

```bash
curl -s -X POST http://localhost:8080/api/league/confirm \
  -H 'Content-Type: application/json' \
  -d '{ "current": { ...a parsed object with "basePreset": null... } }'
```

Expected `409` — an RFC 9457 ProblemDetail carrying the issues array, and
**no row is written**:

```json
{
  "type": "https://readoption.app/problems/league-config-not-ready",
  "title": "League Config Not Ready",
  "status": 409,
  "detail": "League config has blocking issues and cannot be confirmed",
  "issues": [
    { "field": "scoring.basePreset", "severity": "BLOCKING",
      "message": "Reception scoring was not stated and has no safe default — is the league standard, half-PPR, or full PPR?" }
  ]
}
```

### Verify the persisted row

```bash
psql -h localhost -p 5433 -U <user> readoption -c "
  SELECT id, reception_format, passing_td_points, interception_points,
         te_reception_bonus, team_count, superflex_slots, flex_eligible,
         playoff_teams, playoff_start_week, playoff_end_week, tactics
  FROM league_config
  ORDER BY id DESC
  LIMIT 1;"
```

Expect `te_reception_bonus = 0.50` on a TE-premium league (`0.00` otherwise),
`flex_eligible = 'RB,TE,WR'` (sorted CSV), nullable playoff columns filled only
if stated, and `tactics` as a JSONB document (or `NULL` for a tactics-free
league).

## 7. Not in Phase 3

Captured-but-inert or explicitly deferred:

- The **consumers** of the captured config: replacement-level/VORP math over
  the roster shape, correlation-aware stacking (which is also why stacking
  stays in `freeformNotes` rather than a typed field), and playoff
  strength-of-schedule over the playoff columns — all Phase 4.
- Teams entity, NFL schedule ETL, depth-chart/role ingestion.
- The frontend (deferred until after Phase 4); these endpoints are exercised
  by curl/tests only.

## 8. Drift from spec

The built code diverges from `docs/specs/phase-3.md` in the following ways.
Most were deliberately locked by `docs/specs/phase-3-review-1-addendum.md`
(the addendum overrides the base spec where they disagree); one is a
behavioral difference in the drift guard.

1. **`ParsedLeague.tactics` is nullable** (`@Valid` only). The base spec showed
   `@NotNull @Valid DraftTactics tactics`; the addendum (Fix C) removed the
   `@NotNull` — a league with no stated tactics is legitimate and persists
   `tactics IS NULL`.
2. **`ScoringSpec` numeric fields are `BigDecimal`**, bounded with
   `@DecimalMin`/`@DecimalMax`, and all three scoring columns are
   `NUMERIC(4,2)`. The base spec had `Integer` with `@Min`/`@Max`; the addendum
   (Fix A) changed this so fractional formats (e.g. −0.5/INT) are expressible.
3. **`LeagueConfig` is deliberately not `Persistable`** and has no
   `@JsonIgnore` on `getId()`. The base spec (and the repo-wide convention)
   prescribed the Persistable upsert pattern; the addendum (Fix E) stripped it
   because this table uses `IDENTITY` generation and only ever inserts — and
   removing the `@JsonIgnore` is what lets `/confirm` return the generated id.
4. **The parser system prompt is a classpath file**
   (`prompts/league-parser.txt`), loaded fail-fast at startup — not a
   `CustomizationProperties` field as the base spec implied (addendum Fix D;
   a multi-line properties value truncates silently).
5. **`DraftTacticsValidator` exists** as a sibling of `LeagueRulesValidator`
   (addendum Fix B) — not in the base spec's validator list. The addendum's
   *optional* cross-field check (round ≤ total draft length) was **not**
   implemented; the code keeps the flat 1..30 bound, explicitly, to avoid
   coupling the tactics validator to the engine-bound `RosterSpec` across the
   authority split. The addendum offered that escape hatch.
6. **The drift guard flags every changed field, not just unaddressed ones.**
   The base spec said to "flag fields that changed but weren't addressed by the
   correction"; `RefineDriftGuard` deliberately refuses that semantic judgment
   (it would require a model to decide what the correction "addressed") and
   surfaces *all* changes as ASSUMPTION issues naming both values — the
   intended change reads as confirmation, anything else as drift. Deterministic
   over clever.
7. **Two registry constants live outside the resolver's own registry.** The
   base spec said the preset defaults live in the resolver; in the code,
   `TE_PREMIUM_BONUS` and `DEFAULT_PASSING_TD_POINTS` do, but the interception
   default is the engine's `ScoringRules.DEFAULT_INTERCEPTION_POINTS` and the
   no-premium bonus is `ScoringRules.NO_TE_BONUS`. The boundary holds — none of
   these numbers are reachable by the LLM — but the constants are split across
   the two classes.

Everything else matches: the endpoint shapes, `maxRefineTurns = 5`, typed
scoring columns + JSONB tactics, the three-commit structure, no LLM call inside
a transaction, and the spec → resolver → domain three-type split intact.
