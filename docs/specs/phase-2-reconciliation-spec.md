# Read Option — Phase 2 Reconciliation Spec

**For:** Claude Code (multi-file build against the `read-option` repo).
**Authored in:** chat (the *why* and the load-bearing decisions are owned by Sorin).
**Apply existing repo conventions by name** — do not re-derive them: `Persistable`
upsert, `@Builder.Default`, `@PrePersist/@PreUpdate` audit timestamps, no-FK on
landing/derived tables, the per-source sync-service shape, RFC 9457 `ProblemDetail`
via `GlobalExceptionHandler`, the config-driven season boundary
(`readoption.current-season`), and the risk-based four-slice test patterns.

**Decisions locked (confirmed by Sorin — build against these, do not re-litigate):**
- **Option A** — the deterministic engine produces every number; the LLM only
  classifies a disagreement into an enum, applied as a selection rule over stat
  lines already on hand.
- **NUMERIC(7,2)** for raw *and* mart stat columns (V7 + V8); `games_played`
  stays `INTEGER`.
- **Model: Anthropic Sonnet** (Haiku reserved as the cost lever if the contested
  set grows).
- **Confidence is an enum** (LOW/MEDIUM/HIGH), never a double.
- **`FLAG_UNCERTAIN` review state lives in the audit table**, not on the mart row;
  the mart `source` column carries provenance only.
- **Measuring stick: PPR by default** — a *detector*, not a played format. The one
  remaining action is empirical, not a design question: run the §4 dual dry-run
  (PPR vs Standard), confirm the contested sets differ only on reception-contested
  players, and lock PPR. Build with PPR as the configured default now.

---

## 0. Goal

Retrofit a staging→mart transform between `player_projection_raw` (per-source
landing) and `player_projections` (the consensus mart). After this change, the
**only** writer of `player_projections` is the reconciliation step.

**Option A semantics (the spine):** the deterministic engine produces every
number; the LLM only *classifies* a disagreement and returns an **enum**. The
engine applies that enum as a **selection rule over stat lines we already hold**
(a real source's line, or a per-stat median of the sources). The model never
emits a stat or a point. Every value in the mart traces to a real source line or
a median of real source lines.

---

## 1. Migrations

### V7 — raw stat columns `INTEGER` → `NUMERIC`
Rationale: integer rounding injects noise into the cross-source points-dispersion
signal the routing decision depends on.

```sql
-- V7: player_projection_raw stat columns INTEGER -> NUMERIC
ALTER TABLE player_projection_raw
    ALTER COLUMN passing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN passing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN interceptions   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN receptions      TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_yards TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_td    TYPE NUMERIC(7,2),
    ALTER COLUMN fumbles_lost    TYPE NUMERIC(7,2),
    ALTER COLUMN two_pt_conv     TYPE NUMERIC(7,2);
-- games_played stays INTEGER (a game count is genuinely integral; it carries no
-- fractional projection noise into the dispersion signal, unlike stat columns)
```

### V8 — mart stat columns `INTEGER` → `NUMERIC`
Rationale (consequence of Option A): the mart receives a real fractional source
line or a per-stat median; an `INTEGER` mart would re-truncate exactly the
precision V7 preserves. Same column set as V5, same `NUMERIC(7,2)` (decided).

```sql
-- V8: player_projections stat columns INTEGER -> NUMERIC (mirror V7 set)
ALTER TABLE player_projections
    ALTER COLUMN passing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN passing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN interceptions   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN receptions      TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_yards TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_td    TYPE NUMERIC(7,2),
    ALTER COLUMN fumbles_lost    TYPE NUMERIC(7,2),
    ALTER COLUMN two_pt_conv     TYPE NUMERIC(7,2);
```
Update the corresponding entity field types (raw + mart) from integer types to
`BigDecimal` so JPA mapping matches; `ScoringService` already works in
`BigDecimal`, so the scoring path is unaffected.

### V9 — reconciliation audit table
Answers "why is this projection what it is" — feeds the future draft-assistant's
explanations and debugging. No FK (derived/audit, mirrors `player_scoring`).

```sql
-- V9: per-player reconciliation audit
CREATE TABLE player_projection_reconciliation (
    player_id      VARCHAR(20) NOT NULL,
    year           INTEGER     NOT NULL,
    source_count   INTEGER     NOT NULL,
    cv             NUMERIC(6,4),          -- null for single-source (no dispersion)
    route          VARCHAR(20) NOT NULL,  -- CONSENSUS | SINGLE_SOURCE | LLM | LLM_FALLBACK
    llm_verdict    VARCHAR(30),           -- enum name, only when route=LLM
    confidence     VARCHAR(10),           -- only when route=LLM
    chosen_source  VARCHAR(50) NOT NULL,  -- 'consensus' | source name
    rationale      TEXT,                  -- only when route=LLM
    model          VARCHAR(50),           -- only when route=LLM
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, year)
);
```
`route` records whether the model was consulted at all — keep it honest:
`CONSENSUS` = CV under threshold, deterministic median, no model call;
`SINGLE_SOURCE` = only one source had a row; `LLM` = model classified;
`LLM_FALLBACK` = model call failed, fell back to median.

---

## 2. The LLM contract (structured output)

Java types — exact contract:

```java
public enum ReconciliationVerdict {
    TRUST_CONSENSUS,    // engine writes the per-stat median
    FAVOR_HIGH_SOURCE,  // engine writes the highest-points source's line
    FAVOR_LOW_SOURCE,   // engine writes the lowest-points source's line
    FLAG_UNCERTAIN      // engine writes the median; audit flags for review
}

public enum Confidence { LOW, MEDIUM, HIGH }

// BeanOutputConverter target. Confidence is an ENUM, not a double:
// a double is a fake number that implies a calibration the model doesn't have
// and tempts arithmetic on model vibes. Enum keeps the model in classification mode.
public record Verdict(
    ReconciliationVerdict verdict,
    Confidence confidence,
    String rationale
) {}
```

**Mechanism:** `BeanOutputConverter<Verdict>` generates JSON format instructions
from the record and parses the model's text back into `Verdict`. Use it via
`ChatClient` (`.entity(Verdict.class)`); it is best-effort, **not** a guarantee —
the model can return an out-of-enum value or extra prose and the parse can throw.
**Wrap it.** On any failure → route `LLM_FALLBACK` → `TRUST_CONSENSUS` (median).

**Model:** Anthropic Sonnet (decided). The points floor bounds the contested set,
so volume is low; the disagreement-shape reasoning (e.g. TD regression) needs the
judgment. Haiku is the cost lever if the contested set grows large.

**What the model reasons over (anti-theater requirement):** the prompt MUST carry
the **per-stat breakdown** of the disagreement, not just two point totals. The
model classifies the *shape* of the gap (TD-driven = fragile/regresses;
volume-driven = structural; efficiency-driven = in between). Phase 2 is explicitly
"reason about disagreement shape from the stat breakdown" — news/injury/depth-chart
context is a later RAG layer and is NOT faked here.

### Prompt sketch (tunable; this is load-bearing — refine, don't rubber-stamp)

System:
> You classify disagreement between fantasy-football projection sources. You do
> NOT produce numbers. You return a verdict enum, a confidence enum, and a short
> rationale. Reason about the *shape* of the disagreement: touchdown-driven gaps
> are high-variance and regress toward the mean; volume- and role-driven gaps are
> more structural and trustworthy. You have no news, injury, or depth-chart
> information — judge only from the stat breakdown given.

User (templated per contested player):
> Player: {name}, {position}, {team}
> Sources disagree. Per-stat breakdown:
> {for each source: source name, each stat, and its scored {PPR} points}
> Highest-points source: {name} ({pts}). Lowest: {name} ({pts}).
> Where does the gap live (volume / efficiency / touchdowns)? Classify:
> TRUST_CONSENSUS, FAVOR_HIGH_SOURCE, FAVOR_LOW_SOURCE, or FLAG_UNCERTAIN.

---

## 3. Reconciliation algorithm

Endpoint: `POST /api/projections/reconcile/{season}?dryRun={bool}` (default false).

Scope: players with ≥1 source row in `player_projection_raw` for `season`.

**Phase READ (read-only):** for each player, load all source stat lines for the
season.

**Phase REASON (NO DB transaction open):**
For each player:
1. Build a `StatLine` per source; score each in the **measuring-stick format**
   (PPR — decided default; see §4/§5) **in memory** via the existing
   `ScoringService`. These points are throwaway — used only to measure spread.
2. **Points floor:** if the max source points `< readoption.reconcile.points-floor`,
   **skip** (non-draftable; also guards the CV mean against → 0). Count as skipped.
3. **Single source** (only one row): no dispersion. Stage that line as-is.
   route `SINGLE_SOURCE`, `chosen_source` = source name, `cv` = null. No model call.
4. **Two+ sources:** compute `CV = populationStdDev(points) / mean(points)`.
   - Use **population** std dev (÷n) — we have all sources, not a sample. With
     n=2 this reduces to `|a-b|/(a+b)`; the per-stat median is the midpoint. Real
     robustness arrives at n=3 (Sleeper). Write the math general.
   - **`dryRun`:** record `(player, cv)` only; do nothing else. (See §4.)
   - **CV ≤ threshold:** stage the **per-stat median** line. route `CONSENSUS`,
     `chosen_source` = `consensus`. No model call.
   - **CV > threshold (contested):** call the model (§2). Apply the verdict:
     - `TRUST_CONSENSUS` → median line; `chosen_source` = `consensus`.
     - `FAVOR_HIGH_SOURCE` → highest-points source's line; `chosen_source` = that source.
     - `FAVOR_LOW_SOURCE` → lowest-points source's line; `chosen_source` = that source.
     - `FLAG_UNCERTAIN` → median line; `chosen_source` = `consensus`; audit flag (route stays `LLM`, `llm_verdict=FLAG_UNCERTAIN`).
     - model error → median line; route `LLM_FALLBACK`; `chosen_source` = `consensus`.
   - Per-player resilience: one failed model call never aborts the batch.

Collect, per player: the staged mart line, and the audit row.

**Phase WRITE (bounded transaction, chunked, upsert):**
1. Upsert staged lines into `player_projections` (`Persistable` upsert). The mart
   `source` column = `chosen_source`.
2. Upsert audit rows into `player_projection_reconciliation`.

**Phase RE-SCORE (after write):** re-score exactly the touched players for
`season` through the existing future-year scoring path so `player_scoring` is
consistent with the new mart. (Reuse the season-boundary routing; do not
re-score the whole table.)

**Return** a report DTO:
`reconciledConsensus / reconciledLlm / reconciledSingleSource / fellBack / skipped`
counts (mirror the ESPN three-outcome report idiom), plus `season` and
`dryRun` echo.

---

## 4. Dry-run calibration

When `dryRun=true`: run READ + the CV computation in REASON, then **return the CV
distribution** (suggest fixed buckets, e.g. 0–0.05, 0.05–0.10, 0.10–0.15,
0.15–0.20, 0.20+) with a count per bucket and the count that would be flagged
contested at the current configured threshold. **No writes, no model calls, no
re-score.** Purpose: set the threshold empirically so the contested tail is
manageable before running for real.

**Also use dry-run to lock the measuring stick (§5):** run it once with
`measuringStickFormat=PPR` and once with `=STANDARD` and compare the contested
sets. The stick is a *detector*, not an output — PPR points = Standard points +
receptions, so PPR sees every disagreement Standard sees plus reception
(target-share) disagreements; Standard alone has a reception-shaped blind spot.
Expectation: the two sets are near-identical except for reception-contested skill
players, where PPR is the correct detector. If confirmed, lock PPR. (Dual-stick
union — flag if either format trips — is deferred; it only adds the rare
cancellation case at real complexity cost, and the stick being config keeps that
door open.)

---

## 5. Config

`@Validated @ConfigurationProperties(prefix = "readoption.reconcile")`:
- `cvThreshold` (e.g. start `0.12` — **provisional, calibrate via dry-run first**)
- `pointsFloor` (e.g. `20.0` measuring-stick points — provisional)
- `measuringStickFormat` (default **PPR** — a *detector*, not a user format;
  PPR dominates Standard as a disagreement detector. Decided as the default; lock
  it via the §4 dual dry-run. This is config precisely so single→union stays a
  contained change later.)

Validate at the startup boundary (`@NotNull`, sane ranges). Do not hardcode any
of the three in service code.

---

## 6. Transaction phasing (the gotcha)

Never hold a DB transaction open across the model calls. A transaction pins a
pooled connection for its whole lifetime; wrapping bounded-but-slow model calls in
one is a long-running-transaction anti-pattern (connection starvation, lock
pressure). Phase it: **READ → REASON (no txn) → WRITE (txn) → RE-SCORE.** The
write phase and the re-score may be separate transactional methods; if invoked
from the same orchestrator bean, avoid the self-call proxy bypass (separate bean
or self-injection so `@Transactional` actually applies).

---

## 7. Class shape (suggested — fit to repo conventions)

- `ReconciliationService` — orchestrator: READ + REASON phases, returns staged
  results + audit rows. Not `@Transactional` (it spans model calls).
- `ReconciliationWriter` (or method on a separate bean) — `@Transactional` WRITE
  phase: mart upsert + audit upsert.
- `VerdictClassifier` — wraps `ChatClient` + `BeanOutputConverter<Verdict>`;
  builds the prompt from the per-stat breakdown; returns `Verdict`; throws on
  parse/transport failure (caller falls back).
- `DispersionCalculator` — pure: population std dev, mean, CV, the n=2 reduction,
  the divide-by-zero guard. **No Spring, fully unit-testable.**
- `ConsensusBuilder` — pure: per-stat median line; high/low source selection by
  measuring-stick points. **No model, fully unit-testable.**
- `ReconciliationController` — `POST /api/projections/reconcile/{season}`,
  `dryRun` param, report DTO, `ProblemDetail` on failure via the global handler.
- `ReconciliationReport`, `CvDistribution` DTOs.
- `PlayerProjectionReconciliation` entity + repository (`Persistable` upsert).

---

## 8. Tests (risk-based, existing four-slice patterns)

- `DispersionCalculator` — plain unit: CV correctness; n=2 reduction to
  `|a-b|/(a+b)`; population vs sample denominator; mean→0 guard returns/skips, no
  divide-by-zero.
- `ConsensusBuilder` — plain unit: per-stat median (incl. the 2-source midpoint);
  high/low selection picks the correct source line by measuring-stick points.
- **Verdict application** — plain unit with a *stubbed* `Verdict` (no live model):
  each enum maps to the correct staged line and `chosen_source`; `LLM_FALLBACK`
  path on a thrown classifier maps to median + correct route.
- `VerdictClassifier` — narrow: malformed/out-of-enum model output → parse failure
  surfaces as the fallback signal (can mock the `ChatClient`/converter boundary).
- Service slice (Mockito) — phasing: REASON never writes; floor skips
  non-draftable; single-source path; dry-run returns distribution and writes
  nothing.
- `@DataJpaTest` + Testcontainers (`pgvector/pgvector:pg16`) — mart + audit upsert
  idempotency (re-run updates, no duplicate rows); re-score leaves
  `player_scoring` consistent for touched players only.

---

## 9. Resolved: the `source` label & review flag

The mart `source` column is **provenance** — after reconciliation it holds
`consensus` (median paths, including `FLAG_UNCERTAIN`) or the **winning source
name** (`FAVOR_HIGH/LOW`, and `SINGLE_SOURCE`). The **review state** for
`FLAG_UNCERTAIN` lives in the **audit table** (`route=LLM`,
`llm_verdict=FLAG_UNCERTAIN`), not on the mart row (decided). The mart stays a
clean stat line + provenance; anything needing "is this uncertain?" joins the
audit table on `(player_id, year)`.

---

## 10. Pre-flight (precede step 1 of the build)

Land rotowire into `player_projection_raw` with `source='rotowire'` so the raw
table holds ≥2 rows per player at the same staging grain as ESPN. (Rotowire
currently sits directly in the mart — the Phase 1 single-source shortcut. After
this, nothing loads straight to the mart.) Reuse the existing per-source sync /
`Persistable` upsert idiom; this is a feeder, not new logic.

---

## Build order

1. Pre-flight: rotowire → raw (§10).
2. V7, V8, V9 migrations + entity field-type updates (§1).
3. Pure cores + tests: `DispersionCalculator`, `ConsensusBuilder` (§7, §8).
4. `Verdict` types + `VerdictClassifier` (§2).
5. `ReconciliationService` (READ/REASON), `ReconciliationWriter` (WRITE),
   re-score chain (§3, §6).
6. Config + `@ConfigurationProperties` (§5).
7. Controller + dry-run + report DTOs (§3, §4).
8. Remaining tests (§8).
9. Calibrate threshold via dry-run, then run for real.
