# Phase 5.0-b — Completion-shortfall guard (spec)

**Owner:** Sorin. **Executor:** Claude Code. **Scope:** small, single-commit.

**Incident (Run B, 2026-07-22, draft 1385734536872075264 / session 19):**
Sleeper flipped the draft's status to `complete` before its picks array finished
settling. `pollOnce`'s complete-branch ran its final sweep in that gap, captured
166 of 168 picks, marked the session COMPLETE, and stopped — everything reported
green over a silently incomplete record (the plausible-degradation shape).
Evidence: sync log "complete: 166 picks" at 22:15:55; the picks endpoint later
holds 168; DB max(overall_pick_no)=166 (tail gap). The picks were recovered by a
manual relink — the failure is PREMATURE TERMINATION, not data loss, so the fix
is "keep looking until the count is honest, then stop; if it never becomes
honest, halt loudly."

The settle time of Sleeper's back-fill is UNMEASURED (the confirming curl came
minutes after the sweep). The grace default below is therefore generous; the
acceptance draft for this fix measures the real settle window from the WARN
timestamps it produces.

---

## Changes

### 1. `DraftSyncService` — the complete-branch demands count agreement

In `pollOnce`'s `"complete"` case, after the sweep:

- Compute `expected = draft.settings().teams() * draft.settings().rounds()`
  (both from the SAME draft object — no config involvement).
- If cumulative persisted picks `== expected`: current behavior (markComplete
  if needed, return COMPLETE).
- If `< expected`: do NOT markComplete, do NOT return COMPLETE. Log WARN:
  `draft {} complete at Sleeper but picks array holds {}/{} — continuing to poll`
  and return a SYNCING report carrying the shortfall (see 2). The loop keeps
  polling; each poll re-sweeps via the set-difference, so the moment the array
  settles, the normal complete path fires.
- If `> expected`: unobserved variant — throw `IllegalStateException` naming
  both numbers (gate-halt semantics, immediate ERROR; more picks than the draft
  can hold means our model of the draft is wrong).

`markComplete` must only ever run on the full-count path.

### 2. `PollReport` — carry the shortfall

Add field `int shortfall` (0 in every existing path; `expected - total` on the
short-complete path). Update the record's javadoc: non-zero means "Sleeper says
complete but the picks array hasn't settled to the expected count".

### 3. `DraftSyncRunner` — bounded grace, then loud

- New consecutive counter: polls whose report has `shortfall > 0`. Reset to 0
  on any report with `shortfall == 0`.
- When the counter reaches the grace budget: status ERROR, loop stops, error
  message names the numbers, e.g.
  `draft complete at Sleeper but picks settled at 166/168 after 20 grace polls — relink to retry`.
- The existing failure/error-budget path is untouched; this is a separate
  counter for a separate condition.

### 4. `SleeperSyncProperties` — the grace knob

Add `completionGracePolls` to the `Sync` record: `@Min(1) @Max(200) int`,
default via property. `application.properties`:

```
readoption.sleeper.sync.completion-grace-polls=20
```

(20 polls x PT3S = 60s of grace — generous because the settle time is
unmeasured; tune after the acceptance draft reports real WARN-to-settle timing.)

---

## Files

**Modified (only these):**

| File | Change |
|---|---|
| `DraftSyncService.java` | complete-branch count gate + WARN + shortfall + the > expected halt |
| `DraftSyncRunner.java` | grace counter + loud ERROR on exhaustion |
| `SleeperSyncProperties.java` | `completionGracePolls` |
| `application.properties` | the property |
| `DraftSyncServiceTest.java` | new tests below |
| `DraftSyncRunnerTest.java` | new tests below |

No migration, no new files, no endpoint changes. Anything beyond this list:
stop and flag with reasoning BEFORE writing.

---

## Tests

`DraftSyncServiceTest` (inline drafts fine; teams=12, rounds=14 → expected 168):
1. complete + array at 168 → COMPLETE, markComplete called, shortfall 0.
2. complete + array at 166 → report SYNCING, shortfall 2, markComplete NEVER
   called, the 166 picks still inserted (recovery data is never discarded).
3. complete + array at 169 (> expected) → IllegalStateException naming 169/168.
4. Existing complete-path tests updated ONLY if their fixture counts no longer
   satisfy the full-count condition — flag which, do not weaken assertions.
   (`completeFinalSweep` uses a 3-pick fixture against a 10x15 draft: it must
   now expect SYNCING+shortfall, not COMPLETE — this is the incident's exact
   shape and the test rename should say so.)
5. The one-shot-import test (150/150 on the p5 fixture, 10x15) must still pass
   UNCHANGED — full-count import remains COMPLETE in one poll.

`DraftSyncRunnerTest`:
6. Reports with shortfall>0 for `completionGracePolls` consecutive polls →
   ERROR, message contains both counts; loop stopped.
7. shortfall>0 streak broken by a shortfall==0 COMPLETE report → COMPLETE, no
   ERROR (the settle case).
8. Grace counter independent of the failure budget: alternating
   transport-failure and shortfall polls trip NEITHER budget prematurely.

Full suite green; report the count (383 + new).

---

## Deviation protocol

Standing rules: named files only; stop-and-flag before any addition; one-line
commit; senior self-review of the diff at the end. Spec file may ride the
implementation commit (standing owner policy, phase 4.4.2/5.0-a precedent).
