# Read Option — Phase 2 Feeder-Parity Addendum

**For:** Claude Code (against `read-option`, after the Phase 2 reconciliation build).
**Why:** re-sync surfaced four field-level gaps. Two were ESPN-only nulls (rotowire
already maps them), one was a genuine audit bug (rotowire `source_payload` null),
one was a non-issue (ESPN `two_pt_conv` null is deliberate). This addendum brings
the two feeders to parity and closes the test gap that let the null ship green.

**Apply repo conventions by name** (do not re-derive): `Persistable` upsert,
`@Builder.Default`, the `@JdbcTypeCode(SqlTypes.JSON)` payload field, the feeder
sync-service shape, the risk-based slice tests.

**Decisions locked (confirmed by Sorin):**
- Rotowire `source_payload` → **scoped typed object (Option 1)**: serialize the
  `SleeperProjection` the mapper mapped from, mirroring ESPN serializing its
  `StatEntry`. Uniform rule both feeders obey: **store the typed object you mapped
  from** — not the raw wire node (ESPN's full record is ~5,000 lines of splits and
  variants you'll never replay; the scoped node is what supports the decision).
  The column stays (not dropped): the source is *mutable* — projections revise
  through the season — so the audit must pin what the verdict was computed from at
  decision time; re-fetching would give today's data, not that. **No envelope
  record, no `SleeperClient` change, no RestClient migration** — the tested client
  is untouched; the fix lives entirely in the mapper.
- ESPN `games_played` → **hardcode 17**, mirroring rotowire. Do **not** map a stat
  id for it (keeps the two feeders symmetric).
- ESPN `team` → **copy from the resolved canonical `Player`**, not a proTeamId→abbrev
  map. `player.team` stays the single source of team truth (the future
  good-offense / QB-WR-stack reasoning reads it from there).
- ESPN `two_pt_conv` null → **no action** (deliberate from the collection layer).

---

## 1. Rotowire `source_payload` — scoped capture (Option 1)

Root cause: `RotowireProjectionMapper.toRaw` builds the row but never sets
`source_payload`, so the column lands null. ESPN populates it by serializing the
`StatEntry` it mapped from; do the symmetric thing here — serialize the
`SleeperProjection`. The fix is entirely in the mapper; the `SleeperClient`, the
sync service, and the DTOs are untouched.

In `RotowireProjectionMapper`:

```java
private final ObjectMapper objectMapper;

public RotowireProjectionMapper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
}
```

Set the payload on the builder (the typed object the mapper mapped from):
```java
.sourcePayload(serialize(projection))
```

Mirror ESPN's null-safe serializer exactly — an audit payload must never fail a row:
```java
private String serialize(SleeperProjection projection) {
    try {
        return objectMapper.writeValueAsString(projection);
    } catch (JsonProcessingException e) {
        return null;   // audit payload only — never fail a row over it
    }
}
```

That is the whole change: one injected field, one builder line, one private method
copied from `EspnProjectionMapper`. No `SleeperProjectionEnvelope`, no
`readTree`/`treeToValue`, no client return-type change.

---

## 2. ESPN parity — `games_played`, `team`, and a dedupe

In `EspnProjectionMapper`:

### 2a. `games_played`
```java
private static final int SEASON_GAMES = 17;   // mirror rotowire; a season projection means a full season
...
.gamesPlayed(SEASON_GAMES)
```

### 2b. `team` — propagate from the resolved player (no proTeamId map)
ESPN gives `proTeamId` (an integer), but the sync already resolves every row to a
canonical `Player` before mapping, and that player carries the real team
abbreviation. Source `team` from there — building an id→abbrev map would create a
*second* source of team truth on the raw row, free to drift from `player.team`.

- `EspnProjectionMapper.toRaw` gains a `String team` parameter; set `.team(team)`.
- `EspnProjectionSyncService.sync` passes `player.get().getTeam()`
  (confirm the getter name on `Player`) into the mapper call.

Safe because resolution is a hard filter: unresolved ESPN players are dropped to
the review queue and never reach the mapper, so every mapped row has a resolved
player with a team.

### 2c. Delete the duplicate `interceptions` mapping
`toRaw` sets `.interceptions(decimalStat(stats, EspnStatId.INTERCEPTIONS))` twice
(once in passing order, once after `receivingTd`). Same value, harmless, but dead
code — **delete the second occurrence**, keep the first.

---

## 3. Test gap — assert the audit columns that rotted under green

The original `source_payload` null shipped because the feeder tests asserted the
typed columns and ADP but never the payload. Close it on both feeders:

- **Rotowire** (`RotowireProjectionSyncServiceTest` + its integration test): the
  client mock and fixtures are unchanged (still `List<SleeperProjection>`). Assert
  the landed row's `source_payload` is **non-null** and deserializes back to a
  `SleeperProjection` whose `playerId` matches the row (round-trip).
- **ESPN** (mapper/integration test): assert the landed row's `team` equals the
  resolved player's team and `games_played == 17`. Assert `two_pt_conv` is null
  (lock the deliberate decision so a future "fix" trips a test).
- Principle for both: a column that is a stated requirement gets an assertion, or
  it is load-bearing in the spec and undefended in code.

---

## 4. Deferred / unchanged

ESPN's `source_payload` is **already correct** — it serializes the scoped
`StatEntry` it mapped from (~50 lines: the season projection, ESPN's own
`appliedTotal` as an independent cross-check on the scoring engine, and stat ids
beyond what we model). That is the same rule rotowire now follows, so there is no
asymmetry to close and nothing to do on the ESPN payload.

### 4a. `SleeperClient` → RestClient migration (deferred, unchanged)
Independent of this fix and no longer entangled with it — Option 1 never touches
the client. Standardize the HTTP idiom when convenient, in its own commit.

### 4b. Per-format ADP feeder (deferred, unchanged)
Three-format mart ADP is preserved-on-upsert, not repopulated for new players.
The real fix sources per-format ADP directly (Sleeper/FantasyPros expose it per
format) — do **not** derive three columns from the single raw `adp`; PPR and
Standard ADP genuinely differ.

---

## Build order

1. §1: rotowire `source_payload` — `ObjectMapper` into the mapper, `serialize`,
   `.sourcePayload(serialize(projection))`. Mapper-only.
2. §2a–2c: ESPN parity (games, team, dedupe).
3. §3: feeder test assertions (no fixture rewrite — client/DTOs unchanged).
