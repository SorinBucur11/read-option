# Sleeper wire fixtures (Phase 5.0 / 5.0-a)

Every fixture is a copy of a captured probe payload in
`../../../../docs/specs/phase-5-0-probes/` — nothing here is reconstructed from memory.

Verbatim probe copies (real Sleeper responses, captured 2026-07-14/15):

- `draft-league-complete.json` ← `p4-draft.json` — the league draft object.
  Pins `reversal_round` present as an explicit `0` (league drafts), `draft_order`
  populated (slot 4), status `complete`.
- `picks-complete-150.json` ← `p5-picks-complete.json` — the full 150-pick array
  (10 teams × 15 rounds, all `is_keeper` null). The snake cross-check regression
  anchor, Barkley-style.
- `picks-live-3.json` ← `p6a-picks-live-t0.json` — the mid-draft 3-pick array.

Real probe copies, quick-create draft (captured 2026-07-14/15, recorded as p7/p8
in Phase 5.0-a). Content-faithful, not byte-faithful — the original curl output
passed through pretty-printing before being recorded, so the field set and
values are exact but byte formatting is reconstructed:

- `draft-pre_draft.json` ← `p7-draft-pre_draft.json` — status `pre_draft`,
  `draft_order` explicitly null, `reversal_round` ABSENT from settings (distinct
  from the league draft's explicit 0), `slot_to_roster_id` already present as an
  identity map.
- `draft-drafting.json` ← `p8-draft-drafting.json` — the same draft flipped to
  `drafting`, `draft_order` populated with the probe user at slot 7,
  `reversal_round` still absent.
