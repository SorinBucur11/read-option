# Phase 4.4.2-B — Rookie Categorization Fix (amendment spec)

**Type:** amendment to phase-4.4.2; second commit within the phase.
**Trigger:** 4.4.2 acceptance P1 re-probe FAILED as frozen — sophomores presented
under a rookie heading ("crown jewel", "best rookie on the board outright").
**Size:** one commit. No migration, no schema, no new tools.

---

## 1. Diagnosis (evidence-closed)

- The 4.4.2 data fix worked: the model demonstrably discriminates
  (isolates Love as "a true 2026 rookie with no NFL production yet";
  attributes 2025 production to every sophomore). The residual defect is
  **categorization over correct facts**: it knows Jeanty is second-year and
  headlines him as a rookie anyway.
- **Discriminating probe (2026-07-12):** "Who are the best TRUE rookies…"
  → perfect restriction (Love isolated; sophomores in an explicitly labeled
  second-year section; boundary held through the bottom line). Conclusion:
  capability proven; the defect is DEFAULT SCOPING, not ability.
- **Mechanism:** the 4.4.2 sophomore label itself contains the attractor —
  "2nd NFL season (**rookie year** 2025)" puts the token "rookie" on every
  sophomore profile. Retrieved text is instruction surface; the vocabulary
  invited the miscategorization it was meant to prevent.
- Consequence for the fix: **the label change is primary; the prompt line is
  belt-and-suspenders.** Do not instruct the model around bad vocabulary —
  stop emitting the bad vocabulary (the F9 shape, one layer up).

## 2. Changes — file list (executor commits ONLY these; deviate-and-flag)

### 2.1 `agent/ProfileScoringService` — sophomore label rewording

```
1 -> "2nd NFL season (entered the NFL in " + (currentSeason - 1) + ")"
```

The word "Rookie" must appear in exactly ONE derivation output: the
`years_exp == 0` string. No other change to `deriveExperience`, the ordinal
helper, or the constants. Update the method javadoc's wording note if it
quotes the old string.

### 2.2 `agent/ProfileScoringServiceTest` — contract strings updated

- `deriveExperience(1, 2026)` → `"2nd NFL season (entered the NFL in 2025)"`
- Season-interpolation pin: `deriveExperience(1, 2027)` →
  `"2nd NFL season (entered the NFL in 2026)"`
- All other cases unchanged.

### 2.3 `src/main/resources/prompts/draft-agent-system.txt` — one sentence

Append to the **State and memory discipline** (4.M.1) section — it is a
conversational-scoping rule, so it lives in the system prompt, NOT in a tool
description:

> A rookie is a player in his first NFL season — the profile's experience
> field marks these explicitly. Second-year players are never rookies: do not
> group them under a rookie heading or award them rookie superlatives. When
> asked about rookies, you may mention second-year players only in a clearly
> separated, explicitly labeled section.

No other prompt edits. If any test pins the prompt file's content, updating
it is spec-named work, not a deviation.

## 3. Acceptance (criterion REWRITTEN for the real defect)

1. **Fresh session.** Re-run P1 **verbatim** — the original wording, WITHOUT
   "true": "Who are the best rookies for this years draft?"
2. **Pass criteria:**
   - Only `years_exp = 0` players are presented AS rookies (headings,
     groupings, superlatives — "best rookie", "top rookie" — reserved for them).
   - Second-year players MAY appear, but only in an explicitly labeled
     second-year section and never called rookies.
   - Grounding unchanged: cited numbers trace to tool results.
3. **Token baseline ×2 (covers BOTH 4.4.2 prompt deltas in one capture):**
   fresh session per run, the standing instrument question verbatim
   ("Which player should I pick?"), two runs, iter-0 input recorded.
   Expect byte-identical between runs; delta vs 2430 = the description line
   (commit A) + the system-prompt sentence (commit B). Record in
   `docs/specs/phase-4.4.2-acceptance/`.

## 4. Ledger lines to record at phase closure (no code)

- **P1 first re-run: FAIL as frozen** — data defect closed, categorization
  defect surfaced; criterion rewritten (this spec §3). The honest sequence
  stays in the ledger; acceptance criteria are frozen per attempt, never
  retro-fitted to a transcript.
- **Prose rounding watch item:** two runs rounded 228.88 → 229 and → 228
  (direction unstable); a third run cited 228.88 verbatim. Source grounding
  held; presentation-layer rounding noted, no action.
- **Ungrounded roster fact watch item:** "with Caleb Williams" — a
  current-tense roster claim from model memory, no tool source, no date.
  The name-recognition pattern one category over (rosters, not experience).
  Graduation evidence threshold: recurrence in acceptance transcripts →
  candidate for a roster-facts grounding line (the 4.M.1 shape).
- **Spec-file waiver** (commit A): spec rode its implementation commit,
  owner-waived.

## 5. Out of scope

- Any dedup, roster-grounding prompt work, or news-layer change.
- `metadata.rookie_year` landing (fallback unchanged).
