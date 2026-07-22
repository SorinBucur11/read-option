# Phase 5.0 — Sleeper Live Draft Sync (spec)

**Owner:** Sorin. **Executor:** Claude Code. **Chat design session:** 2026-07-14/15.
**Probe evidence:** `phase-5-0-probes/` (p1–p6a + the pre_draft/drafting
draft-object pair). Design decisions D1–D7 resolved in chat; this spec is their
executable form.

---

## Scope

A read-only poller that mirrors a live Sleeper draft into an existing
`draft_session`/`draft_pick` pair, with per-pick validation, so the Phase 4 agent
answers from live state. Single user, single concurrent draft.

**Non-goals (do not touch):**
- No league `scoring_settings` importer (Phase 5.1).
- No K/DEF board or scoring changes. K/DEF **picks** are persisted (players already
  in the table; V12 FK resolves — verified by psql 2026-07-15).
- No changes to `PlayerSyncService`, `SleeperClient`, or any sync/mapper code.
  The merge-preservation block in `PlayerSyncService` is load-bearing and fragile
  by design — no drive-by refactors.
- No agent/prompt changes of any kind. **Zero prompt delta this phase** — the 2564
  baseline must remain valid.
- No scheduling/automation beyond the manual start endpoint.

---

## Migration

**V18__draft_session_sleeper_draft_id.sql**

```sql
-- V18: bind a draft_session to a live Sleeper draft.
-- NULL = manual session (the only kind before Phase 5.0).
-- UNIQUE: one session per external draft, ever — relinking an already-synced
-- draft must resume its session, not create a sibling.
ALTER TABLE draft_session
    ADD COLUMN sleeper_draft_id VARCHAR(32);

ALTER TABLE draft_session
    ADD CONSTRAINT uq_draft_session_sleeper_draft_id UNIQUE (sleeper_draft_id);
```

(Postgres UNIQUE permits multiple NULLs — manual sessions are unaffected.)

---

## Configuration properties

```
readoption.sleeper.username=            # fail-fast if a sync is started without it
readoption.sleeper.sync.poll-interval=PT3S
readoption.sleeper.sync.error-budget=5  # consecutive poll failures before loud halt
```

Bind via a `SleeperSyncProperties` record in `sleeper/` (constructor-bound,
`@ConfigurationProperties("readoption.sleeper")`), same idiom as `AgentProperties`.

---

## Files

**New:**

| File | Package | Role |
|---|---|---|
| `V18__draft_session_sleeper_draft_id.sql` | `db/migration` | above |
| `SleeperDraftClient.java` | `sleeper/` | RestClient: user-by-username, draft object, picks array |
| `SleeperDraftDto.java` (or split records) | `sleeper/` | see DTO contract |
| `SleeperSyncProperties.java` | `sleeper/` | properties record |
| `DraftSyncService.java` | `draft/` | `pollOnce` — the testable core |
| `DraftSyncWriter.java` | `draft/` | `@Transactional` batch insert (separate bean for the proxy, news-writer precedent) |
| `DraftSyncRunner.java` | `draft/` | virtual-thread loop registry, start/stop/status |
| `DraftSyncController.java` | `draft/` | three endpoints |
| `DraftSyncStatus.java` | `draft/` | enum: `WATCHING, SYNCING, COMPLETE, STOPPED, ERROR` + report record |

**Modified (only these, only as stated):**

| File | Change |
|---|---|
| `DraftSession.java` | `sleeperDraftId` field (nullable, mapped to V18 column) |
| `DraftService.java` (or wherever `recordPick` lives) | reject manual picks on synced sessions — see guard |
| `application.properties` / `-local` | the three properties |

Executor commits ONLY the files named here (explicit paths, never `git add .`).
Any file the implementation genuinely needs beyond this list → **stop and flag,
with reasoning, before writing it** (the 4.4.1 repository-omission lesson: the
list is a safety net, the deviation protocol is what makes it safe).

---

## DTO contract (Jackson 3, records)

`SleeperDraft`:
- `draftId`, `status` (String — see lifecycle), `type` (String), `season`
- `settings`: nested record with `teams`, `rounds`, `pickTimer` (int) and
  **`reversalRound` as `Integer`** — nullable, NEVER `int`. Probe fact: the field
  is absent on drafts created via quick-create, explicit `0` on league drafts.
  Absent and zero are distinct observations; a primitive would erase that, and
  the halt log must be able to say which it saw.
- `draftOrder`: `Map<String, Integer>` — **nullable** (null until status flips to
  `drafting`; probe-verified).
- `slotToRosterId`: NOT mapped. v1 operates entirely in slot-space; roster
  identity is unused. Do not map fields nothing consumes.
- `metadata`: NOT mapped (5.1 reads `scoring_type` from it; not now).

`SleeperDraftPick`:
- `pickNo` (int), `round` (int), `draftSlot` (int), `playerId` (String),
  `isKeeper` (**`Boolean`**, nullable — null observed on all 150 probe picks;
  non-null is an unobserved variant and halts the sync),
  `rosterId` (Integer).
- per-pick `metadata` blob: NOT mapped. `player_id` joins to the source of truth.

`@JsonIgnoreProperties(ignoreUnknown = true)` throughout (Sleeper adds fields
freely; we consume a fixed subset).

---

## `pollOnce(draftId)` contract — ordered gates

`DraftSyncService.pollOnce` returns a `PollReport` (status observed, session id
if any, picks inserted this poll, cumulative picks). One invocation = one poll.
No transaction spans the HTTP fetch (news-embedding precedent).

**1. Fetch the draft object.** Exhaustive status match:
- `"pre_draft"` → return WATCHING (no session may exist yet — `draft_order` is
  null; probe-verified).
- `"drafting"` → step 2, then step 3.
- `"complete"` → step 3 (final sweep), then mark session `COMPLETE`, return
  COMPLETE. (Linking an already-complete draft is thereby a one-shot import —
  free feature, worth a test.)
- **any other value → throw** `IllegalStateException` naming the value. Unknown
  lifecycle states halt loudly; a paused-draft status, if it exists, will
  introduce itself this way and get a decision, not a guess.

**2. Ensure session (first `drafting` observation only).** If no `draft_session`
with this `sleeper_draft_id`, create one — gates in order, each rejection loud
and specific:
- `type != "snake"` → halt.
- `reversalRound` non-null AND non-zero → halt ("3RR drafts unsupported"; log
  the value). Null or 0 proceeds.
- Resolve `readoption.sleeper.username` → user_id via the user endpoint (cache
  in the runner for the sync's lifetime). `draftOrder` missing that user_id →
  halt ("linked user is not in this draft").
- `leagueConfig.teamCount != settings.teams` → halt, both values in the message.
- Create: `season` from draft, `teamCount = settings.teams`,
  `userSlot = draftOrder.get(userId)`, **`totalRounds = settings.rounds`** (the
  draft object is the observed fact; the config's roster-derived count is
  intent). `status = ACTIVE`, `sleeperDraftId`, `leagueConfigId` from the link
  request. Snapshot-at-creation holds — creation was deferred until every fact
  existed.
- Log the disclosure line once:
  `config expresses N roster rounds; draft has M — K/DEF slots not modeled in config`.
- **Deviation-flag point:** if the existing manual creation path derives
  `totalRounds` internally from `LeagueSettings` and its shape resists an
  externally-supplied rounds value, FLAG rather than refactor — the owner
  decides between an overload and a separate factory method.

**3. Sync picks.** Fetch the full picks array. Compute the set-difference on
`pick_no` against persisted picks for the session (NOT a max-watermark: same
cost, idempotent by construction, restart-safe — the set-difference IS the
recovery mechanism). For each new pick, in `pick_no` order:
- `isKeeper != null` → halt loud (unobserved variant).
- **Snake cross-check gate:** derive slot and round from `SnakeOrder` arithmetic
  over (`pickNo`, `teamCount`); compare to Sleeper's reported
  `draftSlot`/`round`. Mismatch → halt loud, message MUST include pick_no, both
  values, and the hint `"slot mismatch — traded pick?"` (pick trading is enabled
  on the owner's real league; the halt must be self-diagnosing on draft night).
- Survivors batch-insert via `DraftSyncWriter` (`picked_at` = observation time —
  Sleeper picks carry no timestamp; comment this semantic in the writer).
  `player_id` verbatim; K/DEF ids (`"LAR"`, numeric kicker ids) resolve against
  the already-landed rows.

A DB constraint violation on insert (composite PK or `uq_draft_pick_player`)
should be impossible (single-writer by prevention, below) — treat as a poll
failure: log, count against the error budget, never swallow.

---

## Single-writer guard

In the manual `recordPick` path: if the session's `sleeperDraftId` is non-null →
**409** ProblemDetail, `"session is Sleeper-synced; picks arrive via sync"`.
This resolves `TODO(4.x)` (concurrent-pick collision) by prevention; the V12
composite PK remains the backstop. Remove the TODO comment in the same change.

---

## `DraftSyncRunner`

- Registry: `ConcurrentHashMap<String, SyncHandle>` keyed by draftId. One
  running loop per draft; `start` on an already-running draftId → 409. Starting
  a draft whose session already exists (restart after crash / relink) is
  legitimate — the set-difference catches up. Mid-draft linking is equally
  legitimate for the same reason.
- Loop: one **virtual thread** per sync (`Thread.ofVirtual().name("sleeper-sync-" +
  draftId).start(...)`). Body: `pollOnce` → sleep `poll-interval` → repeat.
  Terminal statuses (COMPLETE, ERROR, STOPPED) end the loop.
- Error budget: `error-budget` CONSECUTIVE `pollOnce` failures → status ERROR,
  loop stops, last exception in the status report. A success resets the counter.
  Halts from the validation gates (step-2/step-3 `IllegalStateException`s) are
  immediate ERROR — no retry: retrying a 3RR draft five times cannot make it snake.
- `stop(draftId)`: cooperative flag → status STOPPED. Registry entries for
  terminal syncs are kept for status queries until a new `start` replaces them.
- In-memory only. Same non-problem as in-memory ChatMemory: one user, one
  sitting; a crash loses only the loop, and restart + set-difference recovers.

---

## Endpoints (`DraftSyncController`)

| | | |
|---|---|---|
| `POST` | `/api/sleeper/sync` | body `{draftId, leagueConfigId}` → 202 + status report. Validates draft exists via client before starting the loop. |
| `GET` | `/api/sleeper/sync/{draftId}` | status report: state, sessionId (nullable), picksSynced, lastPollAt, error (nullable) |
| `POST` | `/api/sleeper/sync/{draftId}/stop` | 200 + final report; 404 if unknown draftId |

RFC 9457 ProblemDetail for all rejections, existing handler conventions.

---

## Tests (risk-based)

1. **DTO parsing against the REAL probe payloads** — commit the probe JSONs as
   test fixtures (`src/test/resources/sleeper/`): the pre_draft draft object
   (`draftOrder` null, `reversalRound` ABSENT → field null), the drafting object
   (`draftOrder` populated, slot 7), the league draft object (`reversalRound`
   present as 0 → field zero — the absent/zero distinction pinned), the p6a
   3-pick array, the p5 150-pick array.
2. **`pollOnce` unit tests** (mocked client + repos): pre_draft → no session
   created; first drafting poll → session with correct snapshot
   (teams/rounds/slot from the fixture); each creation gate's rejection
   (non-snake, reversalRound=3, user absent from draftOrder, teamCount
   mismatch); set-difference inserts only unseen pick_nos; `isKeeper=true` →
   halt; unknown status `"paused"` → halt naming the value; complete → final
   sweep + session COMPLETE.
3. **Snake cross-check over the full p5 fixture:** 150/150 pass against
   `SnakeOrder`; then a mutated copy (one `draft_slot` altered) → halt with the
   traded-pick message. Real data as the regression anchor, Barkley-style.
4. **Single-writer guard:** `recordPick` on a session with `sleeperDraftId` set
   → 409.
5. **Runner lifecycle** (mocked `DraftSyncService`): error budget counts
   consecutive failures and resets on success; gate-halt → immediate ERROR;
   stop → STOPPED; duplicate start → 409.
6. **Writer** idempotency at the DB level is covered by the service-level
   set-difference tests; a dedicated `@DataJpaTest` is optional cleanup, not
   required.

Expected suite: 347 green + new. No existing test may change (zero prompt delta,
no touched behavior).

---

## Acceptance runbook (sketch — full runbook authored at acceptance, criteria
frozen before the run)

One bot draft ≈ 5 minutes per iteration:
1. Link pre-draft → assert NO session row (psql), status WATCHING.
2. Start draft in Sleeper → session appears; psql-verify slot, teams=10,
   rounds=15, sleeper_draft_id.
3. Mid-draft: `curl` picks + `SELECT count(*)` — counts match; cross-check
   mismatches = 0 (grep the log).
4. **Kill the app mid-draft, restart, re-link** → catch-up with no dupes, no
   gaps (psql count + distinct pick_no continuity). The 4.2 DB-kill drill's
   sibling.
5. Mid-draft agent question → grounded answer citing live board numbers; roster
   reflects synced picks.
6. Completion → loop self-stopped (COMPLETE), final DB `player_id` set equals
   the final curl's set (including all 20 K/DEF picks), session COMPLETE.
7. Known-and-disclosed gap: bench-slot accounting reads up to 2 low after K/DEF
   picks (config cannot model those slots until 5.1) — graded against this
   disclosure, not failed as a surprise.

---

## Deviation protocol (standing rules, restated)

- Commit ONLY spec-named files; explicit paths.
- Any needed-but-unnamed file: stop, flag, reason — before writing.
- The two named deviation-flag points: `totalRounds` sourcing at session
  creation; anything about `recordPick`'s current shape that resists the guard.
- Correctness vs standardization stay in separate commits (the `SleeperClient`
  RestClient migration remains deferred and out of scope).
- Executor-authored docs, if any, labeled as such.
