# Phase 4.4 Commit A — espn_id crosswalk repair (2026-07-11)

One repair pass over the 8 draftable players (QB/RB/WR/TE with a 2026 projection)
carrying a null `espn_id`, per phase-4.4 spec §3. Source: DynastyProcess
`db_playerids.csv`, fresh snapshot pulled 2026-07-11 from
`https://github.com/dynastyprocess/data/raw/master/files/db_playerids.csv`.

## Method

All 8 are 2026 rookies added to `player` after the bundled CSV snapshot
(2026-06-27); none has a `sleeper_id`-keyed row even in the fresh snapshot, so the
deterministic `sleeper_id → espn_id` join resolves **zero** of them. Resolution
fell back to the CSV's own `merge_name` key (exact match, the column DynastyProcess
maintains for this purpose — not fuzzy matching), then **every candidate espn_id was
verified against ESPN's athlete API** (`site.web.api.espn.com/.../athletes/{id}`):
name, position, and team must all match the Sleeper row before the update is applied.

## Outcome per player

| sleeper_id | Player | Pos/Team | espn_id | Outcome |
|---|---|---|---|---|
| 13272 | Carson Beck | QB/ARI | 4430841 | **repaired** — ESPN confirms Carson Beck, QB, ARI |
| 13270 | CJ Daniels | WR/LAR | 4605951 | **repaired** — ESPN confirms CJ Daniels, WR, LAR |
| 13417 | De'Zhaun Stribling | WR/SF | 4710714 | **repaired** — ESPN confirms De'Zhaun Stribling, WR, SF |
| 13400 | Justin Joly | TE/DEN | 4912052 | **repaired** — ESPN confirms Justin Joly, TE, DEN |
| 13324 | Matt Hibner | TE/BAL | 4432260 | **repaired** — CSV row is `matthew hibner`; ESPN confirms Matthew Hibner, TE, BAL |
| 13354 | Eric McAlister | WR/– | — | **unresolvable** — CSV row exists but its `espn_id` is `NA` |
| 13357 | Lance Mason | TE/SEA | — | **unresolvable** — no row in the fresh CSV under any name variant |
| 13431 | Miles Kitselman | TE/DET | — | **unresolvable** — no row in the fresh CSV under any name variant |

The 3 unresolvable players degrade loudly through the news layer as
`NEWS_UNAVAILABLE_NO_ESPN_ID` (see `NewsVocabulary`). Re-check them against a
future DynastyProcess snapshot if a transcript ever surfaces the gap.

## Repair SQL (idempotent — guarded on `espn_id IS NULL`)

```sql
UPDATE player SET espn_id = '4430841', updated_at = CURRENT_TIMESTAMP WHERE id = '13272' AND espn_id IS NULL; -- Carson Beck
UPDATE player SET espn_id = '4605951', updated_at = CURRENT_TIMESTAMP WHERE id = '13270' AND espn_id IS NULL; -- CJ Daniels
UPDATE player SET espn_id = '4710714', updated_at = CURRENT_TIMESTAMP WHERE id = '13417' AND espn_id IS NULL; -- De'Zhaun Stribling
UPDATE player SET espn_id = '4912052', updated_at = CURRENT_TIMESTAMP WHERE id = '13400' AND espn_id IS NULL; -- Justin Joly
UPDATE player SET espn_id = '4432260', updated_at = CURRENT_TIMESTAMP WHERE id = '13324' AND espn_id IS NULL; -- Matt Hibner
```

Applied to the local DB 2026-07-11. Note: the standing player sync preserves
`espn_id` on merge (the 4.3 F4 fix), so these survive re-syncs; a future
`PlayerIdMappingService` run cannot null them (its update only ever sets values).

## Follow-up finding (surfaced by the 4.4 seed sync, NOT fixed in this commit)

113 player rows carry the **literal string `NA`** as `espn_id` — DynastyProcess's
missing-value marker, landed because `PlayerIdMappingService` guards only against
blank values. None is draftable (zero have a 2026 projection), but 108 sit in the
news-sync scope and fail every run with `For input string: "NA"` (loudly, per
design — the run continues). Recommended fix, its own commit:

1. Data repair: `UPDATE player SET espn_id = NULL WHERE espn_id = 'NA';`
2. Guard in `PlayerIdMappingService`: treat `NA` as missing alongside blank, so
   the next mapping run cannot re-pollute.

This is the 4.3 deferral note firing exactly as predicted ("revisit only if an
ESPN-keyed read path appears") — the news sync is that read path.
