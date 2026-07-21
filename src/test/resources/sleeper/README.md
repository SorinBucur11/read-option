# Sleeper wire fixtures (Phase 5.0)

Provenance — two kinds of file, do not mix them up:

**Verbatim probe copies** (real Sleeper responses, captured 2026-07-14/15,
`docs/specs/phase-5.0-probes/`):

- `draft-league-complete.json` ← `p4-draft.json` — the league draft object.
  Pins `reversal_round` present as an explicit `0` (league drafts), `draft_order`
  populated (slot 4), status `complete`.
- `picks-complete-150.json` ← `p5-picks-complete.json` — the full 150-pick array
  (10 teams × 15 rounds, all `is_keeper` null). The snake cross-check regression
  anchor, Barkley-style.
- `picks-live-3.json` ← `p6a-picks-live-t0.json` — the mid-draft 3-pick array.

**Executor-authored** (this document included — labeled per the deviation
protocol). The chat probe session observed a quick-create draft's pre_draft and
drafting objects, but that pair was never committed to the probes dir; these two
files reproduce the probe-documented facts in the p4 wire shape:

- `draft-pre_draft.json` — status `pre_draft`, `draft_order` explicitly null,
  `reversal_round` ABSENT from settings (the quick-create observation; distinct
  from the league draft's explicit 0).
- `draft-drafting.json` — same draft flipped to `drafting`, `draft_order`
  populated with the probe user at slot 7, `reversal_round` still absent.
