# Read Option — Prior-Season Context Spec (RAG Increment 1)

**For:** Claude Code (against `read-option`, after the reconciliation + feeder-parity builds).
**What this is:** the first retrieval-augmented step. The first verdict run flagged 120/128
contested players `FLAG_UNCERTAIN` — not risk-aversion, but the model honestly hitting the
boundary in its own prompt ("judge from the stat breakdown alone"). Most contested players
are *role/volume disagreements* that cannot be adjudicated from the disputed projections
themselves. The fix is retrieval: inject the player's recent **actual** production as a
baseline, and move the honesty boundary to match.

**Apply repo conventions by name:** the `player_stats` entity + repository, the
`ReconcileProperties` config pattern, the existing `VerdictClassifier` / `ReconciliationService`
shape, the risk-based slice tests.

**Decisions locked (confirmed by Sorin):**
- This is RAG: retrieval = a SQL query over `player_stats`; augmentation = actuals in the
  prompt; generation = the verdict. The seam built here is the one later retrievers
  (depth chart, news, ADP) plug into.
- **Concrete class now, interface later.** One retriever (`player_stats`) → a concrete
  `PriorSeasonContextRetriever`, *not* an interface. Extract the interface when retriever #2
  arrives (trivial refactor, justified by two implementations — not before).
- **Batch retrieval, never N+1.** Fetch all contested-eligible players' actuals in one query
  during READ; the REASON loop reads from a map. 128 LLM calls must not also become 128
  `player_stats` round-trips.
- **The prompt boundary moves because information increased, not to force commitment.** The
  model keeps flagging when history genuinely can't resolve a dispute; it now commits when
  history *can*. Over-flagging and over-committing are both errors.
- Last **3** seasons of actuals (most-recent-first); raw stat lines + `games_played` from
  `player_stats`; empty history is signal ("none on record"), not omission.

---

## 1. The retrieval seam — `PriorSeasonContextRetriever`

A concrete `@Component` with one job: given a set of player ids and the projection season,
return each player's recent actual production.

```java
public record SeasonActuals(
        int year, Integer gamesPlayed,
        Integer passingYards, Integer passingTd,
        Integer rushingYards, Integer rushingTd,
        Integer receptions, Integer receivingYards, Integer receivingTd) {}
```
(Integer, not BigDecimal — these are the genuinely-integral historical columns; the `Number`
scoring contract already covers the mismatch, and we don't score these here, just present them.)

```java
@Component
public class PriorSeasonContextRetriever {
    // Last 3 completed seasons before `season`, most-recent-first, only those that exist.
    // ONE query for all ids — no per-player fetch.
    public Map<String, List<SeasonActuals>> retrieve(Set<String> playerIds, int season);
}
```

Implementation: one repository call —
`playerStatsRepository.findByPlayerIdInAndYearIn(ids, List.of(season-1, season-2, season-3))`
(match the actual `player_stats` field names; add the derived query or a `@Query`) — then
group by `playerId`, sort each list by `year` descending, map each row to `SeasonActuals`.
A player with no rows maps to an **empty list** (not absent from the map — or have the
caller treat `getOrDefault(id, List.of())`).

---

## 2. Where retrieval happens — READ phase, batched

In `ReconciliationService.reconcile`, after `groupByPlayer(...)`:

1. Collect the player ids with **≥2 source rows** (the only players that can be contested).
2. Call `retriever.retrieve(twoSourceIds, season)` **once** → `Map<String, List<SeasonActuals>>`.
3. Hold the map for the REASON loop.

Fetching for all ~310 two-source players (not just the eventual ~128 contested) is deliberate:
the contested set isn't known until CV is computed in REASON, and one indexed query over 310
ids is far cheaper than splitting into a two-pass or fetching lazily. The ~182 consensus
players' actuals simply go unused — acceptable waste for one query.

This stays inside the existing **no-transaction READ/REASON** phase: it's a read, it holds no
transaction across the model calls, and it adds exactly one query to the run.

**N+1 note for the record:** the alternative — fetching actuals per contested player inside
the REASON loop — would add up to 128 `player_stats` round-trips interleaved with 128 LLM
calls. The batch fetch is the deliberate avoidance of that.

---

## 3. Enrich `ContestedPlayer`, format in the classifier

Keep retrieval and formatting separate: the retriever returns typed `SeasonActuals`; the
**classifier** owns all prompt formatting (as it already does for source stat lines).

- Add `List<SeasonActuals> priorActuals` to `ContestedPlayer`.
- In `toContestedPlayer(...)`, populate it from the map:
  `priorActualsMap.getOrDefault(playerId, List.of())`.
- `VerdictClassifier.buildUserPrompt` formats the block (see §4b).

---

## 4. Prompt changes

### 4a. System prompt — move the boundary (config: `readoption.reconcile.system-prompt`)

The current line "judge only from the stat breakdown given" is now false — we're providing
history. Replace the property value with:

```
You classify disagreement between fantasy-football projection sources. You do NOT produce
numbers — you return a verdict enum, a confidence enum, and a short rationale.

Reason about the shape of the disagreement: touchdown-driven gaps are high-variance and
regress toward the mean; volume- and role-driven gaps are more structural. You are also given
the player's actual production from recent seasons. Use it as a baseline: judge which
projection is more consistent with the player's established role and production. A projection
far above a player's established usage is a stronger, riskier claim than one near it.

Make a directional call (FAVOR_HIGH_SOURCE or FAVOR_LOW_SOURCE) when the disagreement shape
and the player's history together point to one source being more grounded — for example, when
the higher projection implies a role the player has never held, or when the gap is mostly
touchdowns that prior production suggests will regress. Use TRUST_CONSENSUS when both
projections are plausible against the player's history and the gap is minor.

Reserve FLAG_UNCERTAIN for genuinely unadjudicable cases: a player with little or no prior NFL
production (rookies, or a player in a new and unestablished role), or a disagreement so broad
across volume, role, and touchdowns at once that prior production cannot settle it. Flagging a
disagreement that the player's history can actually resolve is an error; so is forcing a
confident call on a player whose history cannot settle it.

You have no news, injury, or depth-chart information beyond the production history given —
reason only from the projections and the actuals provided.
```

(Restart to apply — the prompt is read into the bean at construction.)

### 4b. User prompt — inject the actuals block

In `buildUserPrompt`, after the source breakdown and the high/low lines, **before** the
"Where does the gap live" classify line, append a recent-actuals section. Format it as
compactly as `statBreakdown` (reuse the `appendStat` style):

```
Recent actual production:
- 2025: 16 g  rushYd=280 rushTd=2 rec=18 recYd=140
- 2024: 17 g  rushYd=520 rushTd=4 rec=24 recYd=190
- 2023: 15 g  rushYd=610 rushTd=5 rec=20 recYd=170
```

Empty case (rookie / no history):
```
Recent actual production: none on record (rookie or no prior NFL stats).
```

Include `games_played` per season — it lets the model read a low total as role vs. injury,
and is the cheapest anchor for plausibility. Keep the block to the stats that exist (skip
null/zero, same as `statBreakdown`). Token cost is ~3 short lines per contested player — trivial
across 128 calls.

---

## 5. Tests

- `PriorSeasonContextRetriever` (`@DataJpaTest` + Testcontainers): a player with 3 prior
  seasons returns 3 `SeasonActuals`, most-recent-first; a player with 1 returns 1; a player
  with none returns an empty list; the fetch is a single query for a multi-id set (assert
  via the returned map covering all requested ids).
- `VerdictClassifier.buildUserPrompt` (plain unit): the actuals block renders for a populated
  history; the empty-history case renders "none on record"; the block sits before the classify
  line.
- `ReconciliationService` (Mockito): the retriever is called **once** per run with the
  ≥2-source id set (not per player); `ContestedPlayer.priorActuals` is populated from the map.
- The verdict-application and dispersion tests are unaffected — this change touches only the
  classifier's input, not the routing or the math.

---

## 6. Re-run and the inverted acceptance test

This is a **hypothesis to measure, not a guaranteed fix.** Re-run
`POST /api/projections/reconcile/2026` (the same ~12-min serial run) and read the distribution:

```sql
SELECT llm_verdict, count(*) FROM player_projection_reconciliation
WHERE year = 2026 AND route = 'LLM' GROUP BY llm_verdict ORDER BY count(*) DESC;
```

Expected direction: `FLAG_UNCERTAIN` drops from 120 toward ~40–70, with `FAVOR_LOW/HIGH` and
`TRUST_CONSENSUS` absorbing the players whose history now resolves the dispute.

**Inverted acceptance test** — the three players, re-predicted with history in hand:
- **Recognizable backups with establishing history (e.g. Samaje Perine — backup behind Mixon):**
  should now resolve to a grounded `FAVOR_LOW_SOURCE`, with a rationale that cites the *actuals*
  ("prior production shows a backup role; rotowire's low projection matches it"), not smuggled
  training knowledge.
- **Young / thin-history players (e.g. Chris Rodriguez):** should **still** `FLAG_UNCERTAIN` —
  no establishing production to anchor the role dispute. Flagging for the *right* reason now.
- **Rodgers:** genuinely open. His 2025 actual (~3,322 yd) sits between the two projections,
  closer to ESPN — so the model may now `FAVOR_HIGH`, or may still flag on age/regression. Either
  is a **pass if the rationale engages the 3,322 actual.** The failure mode is ignoring it.

The test is whether rationales now cite the actuals to make grounded calls — and whether the
*remaining* flags are the legitimately-unadjudicable (rookies, role changes), not the whole pile.

---

## 7. Deferred / next increments

- **If the flag rate stays high after this:** the next retriever is the answer, not a more
  aggressive prompt. ADP (the market's own role verdict) and depth-chart/role data are the
  next context sources — same seam, new retrievers, *then* extract the interface.
- **ADP coverage caveat (noted for when ADP lands in the mart):** Sleeper redraft ADP is
  `999` (unranked) for many uncertain players — thinnest exactly on contested players — so ADP
  complements prior actuals rather than replacing them.
- **Concurrency + rate limits (separate chapter):** the 12-min serial run is the known next
  optimization; bounded parallelism (~8) with 429 backoff. Independent of this change.

---

## Build order

1. §1: `SeasonActuals` record + `PriorSeasonContextRetriever` + the `player_stats` batch query.
2. §2: wire the one-shot retrieval into READ; hold the map.
3. §3: `ContestedPlayer.priorActuals` + populate in `toContestedPlayer`.
4. §4b: `buildUserPrompt` actuals block (code); §4a system prompt (properties edit).
5. §5: tests.
6. §6: re-run, read the distribution and the three rationales, report back.
