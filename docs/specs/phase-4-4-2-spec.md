# Phase 4.4.2 — Rookie / Experience Exposure (spec)

**Type:** transcript-promoted mini-increment (graduation evidence: 4.4 acceptance P1 probe).
**Size:** one commit. Zero migration. Zero sync/mapper change. Pure exposure.

---

## 1. Context and evidence

The 4.4 acceptance P1 probe (rookie RBs) failed on a missing fact: every "rookie"
the agent cited was a 2025 sophomore. Grounding held throughout — the model cited
what the tools gave it; the tools never offered draft-class information.

**Source audit (2026-07-12) closed the design:**

- `player.years_exp` is already landed, entity-mapped (`Player.yearsExp`), carried
  by `SleeperPlayer` and the mapper, and owned by the Sleeper player sync.
  Row owner = column owner; no cross-sync merge-null exposure.
- Coverage: **0 of 409** draftable (mart) players missing the value.
- Currency verified against both rookie classes and veterans:
  Jadarian Price (2026 class) = 0; Jeanty, Judkins (2025 class) = 1;
  Barkley = 8; Mahomes = 9. Sleeper rolls the counter **before mid-July**, so it
  is correct for the entire draft season.
- **Semantic characterization:** `years_exp` counts *accrued NFL seasons* and
  **freezes for players out of the league** (Billy Price: rookie_year 2018,
  years_exp 6; Bobby Price: 2020 → 5). Valid as calendar experience only for
  active players — which the draftable cohort is. Consequence: the profile label
  may make year-of-entry claims **only for years_exp 0 and 1**, where the live
  counter was explicitly verified; veterans get an ordinal only.
- `metadata.rookie_year` exists in the Sleeper payload (immutable fact) but has
  spotty coverage and is not landed. **Rejected** for this increment; documented
  fallback if a future transcript falsifies `years_exp` semantics.

## 2. Decisions (closed)

- **D1** — Fact: existing `player.years_exp`. No new data.
- **D2** — No migration. No sync change. (V-numbering unaffected; head stays V17.)
- **D3** — Null degrades loudly: `EXPERIENCE_UNKNOWN — years of experience not
  available for this player`. Required despite current 0/409 coverage — a future
  null must be noisy, not silent (loud-failure posture).
- **D4** — **Java derives the label; the LLM cites it.** The model performs no
  experience arithmetic and infers no rookie status from name recognition, news
  tone, or memory. Same boundary as every phase: the classification is computed
  in the engine, the model consumes it.

## 3. Changes — file list (executor commits ONLY these; deviate-and-flag if the seam differs)

### 3.1 `agent/PlayerProfileView` — new `experience` field

Add a `String experience` to the profile view, populated by the derivation below.
Place it adjacent to position/team context so it renders early in the profile text.

### 3.2 `agent/ProfileScoringService` (or wherever the profile view is assembled) — derivation

A small pure method (unit-testable, no I/O). `currentSeason` comes from the
existing `readoption.current-season` property — never hardcoded:

```
deriveExperience(Integer yearsExp, int currentSeason):
  null -> "EXPERIENCE_UNKNOWN — years of experience not available for this player"
  0    -> "Rookie — first NFL season (" + currentSeason + " class, no NFL production yet)"
  1    -> "2nd NFL season (rookie year " + (currentSeason - 1) + ")"
  n>1  -> (n+1) + ordinal + " NFL season"        // e.g. "9th NFL season" — NO year-of-entry claim
```

Rationale for the asymmetry: year-of-entry arithmetic is verified only where the
counter is live; 0/1 are the classes the agent confuses and both were probe-verified.
Veterans get an ordinal because the accrued-seasons freeze makes entry-year
arithmetic unsafe as a general rule.

Ordinal suffix must be correct for 2nd/3rd/4th/…/21st shapes (a tiny helper; test it).

### 3.3 `agent/DraftAgentTools` — `getPlayerProfile` description, one added line

Append to the existing `@Tool` description (do not restructure it):

> "Rookie or experience status comes ONLY from the profile's experience field.
> Never infer a player's experience level from name recognition, news tone, or
> conversation memory. If experience reads EXPERIENCE_UNKNOWN, say so."

**No parameter changes. No new tools. The tool surface stays at six.**

### 3.4 Tests

- **Derivation unit test:** null / 0 / 1 / 2 / 8 cases, exact expected strings
  (the strings are the contract the description points at).
- **Schema-parsing pin:** the existing six-tool schema test must stay green —
  description text changed, parameter surface did not. If any test asserts the
  old description text verbatim, update it in this commit (it is spec-named work,
  not a deviation).

## 4. Acceptance runbook

1. **Fresh session** (ChatMemory is session-keyed — fresh session per run, always).
2. Re-run the P1 rookie-RB probe **verbatim** from the 4.4 acceptance set.
3. **Expected:**
   - Every player cited as a rookie has `years_exp = 0` (2026 class — e.g.
     Jadarian Price-shaped names, not Jeanty/Judkins).
   - If 2025-class players appear, they are labeled 2nd-season, not rookie.
   - Every experience claim traces to a `getPlayerProfile` tool result
     (grounding invariant, unchanged).
4. **Token baseline note:** the description change is a **prompt change** (the
   4.M lesson — schema/description regeneration shifts the prompt). Capture a
   new fresh-turn baseline ×2; expect byte-identical between the two runs and a
   small iter-0 delta vs 2430 (the added description line's token mass, pointing
   the healthy direction). Record in `docs/specs/phase-4.4.2-acceptance/`.
5. No live degradation transcript required (no null exists in the cohort);
   the unit test owns the EXPERIENCE_UNKNOWN path.

## 5. Out of scope

- Landing `metadata.rookie_year` (fallback only, graduation-gated).
- Any sync, mapper, or migration work.
- Near-duplicate news retrieval, uri-tag cardinality, and all other 4.4 carry-forwards.

## 6. Watch item (carry forward)

- **Counter roll timing:** re-verify `years_exp` for the new rookie class next
  offseason before trusting `== 0` again (Sleeper's roll date is characterized
  only as "before mid-July," from one observation cycle).
