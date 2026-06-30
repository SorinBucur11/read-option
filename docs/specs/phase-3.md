# Phase 3 — Natural-Language League Customization (Build Spec)

**For:** Claude Code, executing in the `read-option` repo.
**Read first:** root `CLAUDE.md` (conventions, stack, architecture principle, regression anchors).
**Goal of this phase:** a user describes their league and draft style in plain English; an
LLM parses that into structured, validated objects; a deterministic resolver turns the spec
into the engine's config; the engine consumes it. The LLM translates intent to structure —
**it never originates a scoring number.**

---

## Decisions (defaults chosen; flip a line before running if you disagree)

1. **Resolved scoring rules persist as typed columns** (not all-JSONB). Rules are
   engine-consumed and benefit from `NUMERIC` validate-on-write and queryability. Tactics
   persist as JSONB (LLM-consumed, never queried). *Alternative: all-JSONB for speed.*
2. **`maxRefineTurns = 5`.** The repair loop must terminate; past the cap, return the partial
   object plus unresolved issues and ask the user to state the missing fields explicitly.
3. **Commit 1 (the `ScoringFormat` graduation) is bundled into this phase** as a prerequisite,
   because the parser writes into the parameterized rules object. It is load-bearing and is
   reviewed before Commit 2 builds on it.

---

## Commit plan (three commits, in order, separate)

- **Commit 1 — engine refactor (load-bearing, review closely).** Graduate `ScoringFormat`
  into preset + parameterized `ScoringRules`; thread `Position` into scoring for TE premium.
  Existing scoring numbers MUST NOT move.
- **Commit 2 — `customization` package (net-new).** Parse targets, resolver, validator,
  parsing/refine service, controller, persistence (Flyway V10).
- **Commit 3 — tests.**

Do not combine Commit 1 with Commit 2 — a correctness-bearing refactor and a feature build
stay in separate commits.

---

## Commit 1 — `ScoringRules` graduation (prerequisite)

**Problem:** the current `ScoringFormat` enum bundles two independent axes (reception value,
passing-TD points) into six combined constants and cannot express position-dependent rules
(TE premium). Arbitrary user formats need these decoupled.

**Do:**
1. Add `ReceptionFormat { STANDARD, HALF_PPR, PPR }` — the reception axis only.
2. Introduce a `ScoringRules` value object: the engine's *resolved* scoring config —
   reception points (from format), passing-TD points, interception points, TE reception
   bonus, plus the existing constant rules currently living in `ScoringService`.
   `ScoringService.calculate(...)` consumes `ScoringRules` instead of the bare enum.
3. Thread `Position` into the scoring path so the TE reception bonus applies to TEs only.
   `Scorable` already carries player identity; **keep `StatLine` pure** — pass `Position` as a
   parameter to `calculate(...)`, do not add it to the stat contract.
4. Keep the six existing formats as **named presets** mapping to a `ScoringRules`
   (the enum graduates into a preset registry). The leaderboard's six-format loop keeps
   working by iterating presets.
5. Preserve `BigDecimal` String-constructor arithmetic, `HALF_UP` scale 2, null-safe `points()`.

**Gate (must pass before Commit 2):** the six presets reproduce today's numbers exactly.
Verify against the regression anchors in `CLAUDE.md` (Barkley 208.50/226.00/243.50; Mahomes
−2/INT). This is a refactor — **any number that moves is a bug, not an improvement.**

---

## Commit 2 — `customization` package

Package: `app.readoption.customization` (+ `.validation` subpackage).

### 2a. Parse targets (the LLM's output types)

> **Critical boundary — do not collapse.** The LLM's output type is **not** the engine's
> input type. It emits a narrow *spec* (preset + flags + a few extracted numbers); a
> deterministic *resolver* maps that to the engine's config. The flag→number registry lives
> in the resolver, so the model can say "TE premium" but has **no field to write `1.5` into**.
> This three-type separation (spec → resolver → domain) is the safety boundary of the phase.
> Do not let the LLM emit `ScoringRules` directly.

```java
// ParsedLeague.java — one BeanOutputConverter<ParsedLeague> call yields both partitions.
public record ParsedLeague(
        @NotNull @Valid LeagueRulesSpec rules,    // engine-bound, hard-validated
        @NotNull @Valid DraftTactics tactics) {}  // strategy-bound, soft-validated

// LeagueRulesSpec.java — @Valid cascades into nested records (without it, nested
// constraints never fire). playoff is nullable (most users won't mention it).
public record LeagueRulesSpec(
        @NotNull @Valid ScoringSpec scoring,
        @NotNull @Valid RosterSpec roster,
        @Valid PlayoffSpec playoff) {}

// ScoringSpec.java — every field is exactly one of three kinds (marked).
public record ScoringSpec(
        @NotNull ReceptionFormat basePreset,          // PRESET (closed enum), NO safe default
        @Min(3) @Max(8) Integer passingTdPoints,      // EXTRACTED; null = use preset default
        @Min(-6) @Max(0) Integer interceptionPoints,  // EXTRACTED; null = use preset default
        boolean tePremium) {}                          // FLAG; resolver supplies the value

// RosterSpec.java — pure extraction; ≈ the existing LeagueSettings, now language-populated.
public record RosterSpec(
        @Min(2) @Max(20) int teamCount,
        @PositiveOrZero int qbSlots,
        @PositiveOrZero int rbSlots,
        @PositiveOrZero int wrSlots,
        @PositiveOrZero int teSlots,
        @PositiveOrZero int flexSlots,
        @NotNull Set<Position> flexEligible,
        @PositiveOrZero int superflexSlots,   // roster fact (legal lineups), not scoring
        @PositiveOrZero int benchSlots) {}

// PlayoffSpec.java — captured now, consumed in Phase 4. Cross-field rules in the validator.
public record PlayoffSpec(
        @Min(2) @Max(16) int playoffTeams,        // cross-field: <= roster.teamCount
        @Min(14) @Max(18) int playoffStartWeek,
        @Min(14) @Max(18) int playoffEndWeek) {}  // cross-field: >= playoffStartWeek

// DraftTactics.java — soft. Closed-enum leans + one parameterized constraint + open tail.
public record DraftTactics(
        PositionalStrategy positionalStrategy,            // null = no stated lean
        RiskPosture riskPosture,                          // null = no stated posture
        Map<Position, Integer> earliestRoundByPosition,   // e.g. {QB: 10} = "no QB before R10"
        @Size(max = 20) List<@Size(max = 500) String> freeformNotes) {} // open tactic tail

// enums
public enum ReceptionFormat { STANDARD, HALF_PPR, PPR }
public enum PositionalStrategy { ZERO_RB, HERO_RB, ROBUST_RB, BALANCED, BEST_AVAILABLE }
public enum RiskPosture { UPSIDE, FLOOR, BALANCED }
```

**Why the asymmetry (so you don't "fix" it):** `LeagueRulesSpec` has **no** free-text escape
hatch because a deterministic engine consumes it — prose is useless to it. `DraftTactics` has
`freeformNotes` because the Phase 4 agent (an LLM) consumes it and can reason over prose.
Open-set tactics (stacking, handcuffing, personal quirks) go in `freeformNotes`; they graduate
to typed fields only when a consumer that can act on them exists. **Do not add a typed
`stackQbWithReceiver` field** — its consumer (teams/schedule data + correlation logic) is not
built yet.

### 2b. Resolver (deterministic — the registry lives here)

`LeagueRulesResolver` (`@Component`, no LLM): `LeagueRulesSpec → LeagueRules`
(domain object = resolved `ScoringRules` + roster `LeagueSettings` + `PlayoffFormat`).

- Apply `basePreset` → base `ScoringRules`.
- Overlay extracted deltas where non-null (`passingTdPoints`, `interceptionPoints`);
  else preset default.
- Resolve `tePremium` flag → TE reception bonus from a **registry constant**
  (`TE_PREMIUM_BONUS`, e.g. `0.5` per reception over base). **This number lives only here.**
- Map roster/playoff straight through (pure extraction).

This step must be deterministic and unit-testable with zero model involvement.

### 2c. Validator (object-level, programmatic)

`LeagueRulesValidator` (`@Component`). Runs **after** Jakarta annotation validation passes.
Collects all issues (never fails on the first), classified by severity. Output feeds the
repair prompt, so messages must be value-bearing.

```java
public enum IssueSeverity { BLOCKING, ASSUMPTION }  // (case 1+2: must resolve) vs (case 3: surface)

public record ValidationIssue(String field, IssueSeverity severity, String message) {}
```

Rules to implement:
- `scoring.basePreset == null` → **BLOCKING** ("no safe default; must ask"). *(Belt-and-braces:
  it is also `@NotNull`, but surface it as an explicit issue for the repair prompt.)*
- `playoff != null && playoff.playoffTeams > roster.teamCount` → **BLOCKING**, message naming
  both values (e.g. "Playoff teams (6) cannot exceed league size (4).").
- `playoff != null && playoff.playoffEndWeek < playoff.playoffStartWeek` → **BLOCKING**.
- `!{RB,WR,TE}.containsAll(roster.flexEligible)` → **BLOCKING**.
- `scoring.passingTdPoints == null` → **ASSUMPTION** ("assuming preset default") — non-blocking.

Loop control signal: any `BLOCKING` issue ⇒ cannot proceed. Only `ASSUMPTION` issues remain
⇒ may proceed after one confirmation.

### 2d. Parsing / refine service

`LeagueParsingService` (`ChatClient` + `BeanOutputConverter<ParsedLeague>`), matching the
existing `VerdictClassifier` idiom (separable `parse()` seam, wrapped converter):

- `ParsedLeague parse(String description)` — free text → object.
- `ParsedLeague refine(ParsedLeague current, String correction)` — prompt carries `current`
  serialized as JSON + the correction + the instruction **"change only what the correction
  addresses; preserve every other field exactly."**
- On malformed / out-of-enum output, surface a `BLOCKING` parse-failure issue. **No silent
  default** — config has no safe fallback (this is the deliberate divergence from Phase 2's
  `TRUST_CONSENSUS`).
- System prompt, model, and `maxRefineTurns` in a `@Validated` `CustomizationProperties`
  (`readoption.customization.*`); prompt externalized.

**Refine drift guard:** after `refine`, diff `current` vs the returned object; flag fields
that changed but weren't addressed by the correction (models "helpfully" rewrite untouched
fields). Carrying the typed prior object is what makes this detectable.

### 2e. Orchestration + API

`LeagueConfigController` — **stateless**; the partial object is carried in the payload (no
`ChatMemory`, no session store — the state is a typed object, not a conversation):

```
POST /api/league/parse    { description }                       -> ParseResult
POST /api/league/refine   { current, correction, turn }         -> ParseResult   // enforce maxRefineTurns
POST /api/league/confirm  { current }                           -> LeagueConfig  // commit gate; only writer

record ParseResult(ParsedLeague parsed, List<ValidationIssue> issues, Status status)
enum Status { NEEDS_INPUT, READY }   // any BLOCKING -> NEEDS_INPUT, else READY
```

- `refine` enforces the turn cap; past it, return partial + unresolved issues.
- `confirm` validates `READY`, runs the resolver, persists, returns the resolved config.
  **Nothing is written before confirm** (no-silent-persist; `READY` ≠ committed).
- RFC 9457 `ProblemDetail` on hard failures, via the existing `GlobalExceptionHandler`.

### 2f. Persistence — Flyway **V10** `league_config`

- Surrogate `id`. Resolved **scoring rules as typed columns**: `reception_format` (varchar,
  enum name), `passing_td_points NUMERIC`, `interception_points NUMERIC`,
  `te_reception_bonus NUMERIC`. Roster columns. Playoff columns (nullable).
  `tactics JSONB` (the `DraftTactics`, LLM-consumed, never queried). Audit timestamps.
- `LeagueConfig` entity: Persistable upsert, `@Builder`/`@Builder.Default`, `@JsonIgnore` on
  `isNew()`/`getId()`, `@PrePersist`/`@PreUpdate`. **No FK** (no user table yet).
- JSONB via `@JdbcTypeCode(SqlTypes.JSON)` on the typed `DraftTactics`, same idiom as
  `source_payload`.

---

## Commit 3 — tests

- **Resolver (pure unit):** preset application; null delta → preset default; `tePremium` →
  registry value; TE bonus applies to TE only (not WR/RB).
- **Validator (pure unit):** each issue kind; `playoffTeams > teamCount` BLOCKING;
  end-before-start BLOCKING; null `basePreset` BLOCKING; null `passingTdPoints` ASSUMPTION
  (non-blocking); flex membership.
- **Parsing service (stubbed, no live model):** stub a `ParsedLeague`, assert routing /
  validation / `Status`. Stub a parse failure, assert the BLOCKING parse-failure issue.
- **Refine drift guard:** given `current` + a stubbed refined object that changes an
  unaddressed field, assert drift is flagged.
- **Persistence (`@DataJpaTest` + Testcontainers):** `league_config` upsert idempotency;
  **JSONB `tactics` round-trip through `@JdbcTypeCode(SqlTypes.JSON)`** (this also closes the
  carried-forward Phase 2 gap: the JSONB persistence path was only asserted at mapper level).
- **Controller (`@WebMvcTest`):** parse / refine / confirm happy paths + `NEEDS_INPUT`.
- **Scoring regression (gates Commit 1):** the six presets reproduce pre-refactor numbers
  exactly (regression anchors in `CLAUDE.md`).

---

## Out of scope (do NOT build this phase)

- Depth-chart / role retriever; teams entity; NFL schedule ETL.
- Promoting stacking (or any open-set tactic) to a typed field — stays in `freeformNotes`.
- Any Phase 4 consumer of `DraftTactics` / `PlayoffSpec` / roster value (VORP, stacking,
  playoff strength-of-schedule). These are captured-but-inert this phase.
- `ChatMemory` / conversation memory — deliberately not used; state is carried as a typed object.
- Frontend.

---

## Definition of done

1. Commit 1 merged with the six presets reproducing the regression anchors exactly.
2. `parse → refine → confirm` works end to end on a sample description; `confirm` persists a
   `league_config` row; a contradictory input (e.g. "8 of 6 make playoffs") returns
   `NEEDS_INPUT` with a value-bearing BLOCKING issue and does not persist.
3. `./mvnw clean verify` green, including the JSONB round-trip and the scoring regression.
4. Three separate commits; no LLM call inside a DB transaction; the spec→resolver→domain
   three-type split intact (the LLM never emits `ScoringRules`).
