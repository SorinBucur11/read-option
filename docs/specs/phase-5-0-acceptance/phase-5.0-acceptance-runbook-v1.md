# Phase 5.0 — Acceptance Runbook (FROZEN before first run)

**Criteria frozen:** 2026-07-21, before any run. Failures are recorded as-is
against these criteria; if a criterion turns out to be wrong, it is rewritten
honestly BETWEEN runs and the rewrite is documented — never retroactively
softened (the 4.4.2 rule).

**v1.1 (2026-07-22, still pre-run):** operational detail filled in — request
bodies, exact curl/psql commands, config creation. Two prerequisite facts
corrected against the live DB (see changelog). No criterion changed.

**Artifacts dir:** `docs/specs/phase-5-0-acceptance/` — every curl capture raw
(`curl -s > file`, no formatter), psql outputs pasted verbatim, log excerpts
grepped not summarized.

---

## Conventions & fixed inputs

- **App base:** `http://localhost:8080`. Boot: `./mvnw spring-boot:run` with
  `ANTHROPIC_API_KEY` and `SPRING_AI_OPENAI_API_KEY` in the environment.
- **psql:** `docker compose exec -T postgres psql -U readoption -d readoption`
  (DB container `readoption-db`, host port 5433).
- **Sleeper public API:** `https://api.sleeper.app/v1` — used both to drive the
  drills and as the independent comparison source in A3/A7.
- **Linked user:** `sosososik` = Sleeper user id `87732859926102016`
  (p1-user.json; also `creators[0]` in p4-draft.json).
- **Poll interval:** `PT3S` (`readoption.sleeper.sync.poll-interval`), so
  "within 2 polls" = ≤ 6s.
- **Finding the draftId:** create the mock draft in the Sleeper app, then
  `curl -s https://api.sleeper.app/v1/user/87732859926102016/drafts/nfl/2026`
  — the newest entry's `draft_id` (or read it off the Sleeper draft URL).
- **Sync status report shape** (`GET /api/sleeper/sync/{draftId}`):
  `{draftId, state, sessionId, picksSynced, lastPollAt, error}`.

Shell variables used below: `DRAFT` = Sleeper draft id, `CFG` = league_config
id, `SID` = draft_session id.

---

## Step 0 — pre-flight (must all pass before Run A starts)

- [ ] `DraftSession.java` carries `@PrePersist`/`@PreUpdate` audit-timestamp
      callbacks ON THE ENTITY (the sync's `writer.createSession` bypasses the
      manual service; the callback is the only thing stamping `created_at`).
      Evidence: the two annotated methods, eyeballed.
- [ ] Per-commit conformance: `git show --stat` on all six hashes (e4afb64,
      8ee0f86, 5b26f90, d3be6ad, a9e573d, 5ebe86b) — each commit touches only
      its spec-named files. Evidence: any commit touching more is a finding.
- [ ] `readoption.sleeper.username=sosososik` set locally (not committed).
      Current state: an UNCOMMITTED edit to `application.properties` line 74.
      Evidence: `git diff -- src/main/resources/application.properties` shows
      only that one line changed; the edit stays out of any commit.
- [ ] App boots clean against the V18 schema (`ddl-auto=validate` passes).

## Step 0.5 — league configs (added v1.1; prerequisite for both runs)

DB inventory as of 2026-07-22: `league_config` ids 1–4 exist, **all
12-team** (1–2: PPR/superflex leftovers, 3: the 4.1 acceptance leftover,
4: HALF_PPR no-superflex). **There is no 10-team config** — Run A's config
must be created first via the Phase 3 flow. Run B's 12-team config already
exists (v1's "create first" was wrong; corrected in changelog).

**Create the Run A config.** The description mirrors the Sleeper 10-team
quick-create mock draft exactly (p4-draft.json settings + p2-leagues.json
scoring): teams=10, 1 QB / 2 RB / 2 WR / 1 TE / 2 FLEX / 1 K / 1 DEF / 5 BN,
PPR (`rec=1.0`), `pass_td=4.0`, `pass_int=-1.0`. Config-expressible sum:
1+2+2+1+2+0+5 = **13** rounds (K/DEF not modeled — that IS the A2 disclosure).

```bash
curl -s -X POST http://localhost:8080/api/league/parse \
  -H "Content-Type: application/json" \
  -d '{"description":"10-team PPR league. Starting lineup: 1 QB, 2 RB, 2 WR, 1 TE, 2 flex (RB, WR or TE), 5 bench spots. Passing touchdowns are 4 points, interceptions are -1. No TE premium."}' \
  > 05-parse.json
```

- Expected: `.status` = `"READY"` (no BLOCKING issues; reception format, TD
  and INT values are all stated). If `NEEDS_INPUT`, repair via
  `POST /api/league/refine` with `{"current": <.parsed>, "correction": "...",
  "turn": 1}` and capture that too.

```bash
jq '{current: .parsed}' 05-parse.json > 05-confirm-body.json
curl -s -X POST http://localhost:8080/api/league/confirm \
  -H "Content-Type: application/json" \
  -d @05-confirm-body.json > 05-confirm.json
```

- Record the returned `id` → this is Run A's `CFG`.
- psql verification (creation gate is `team_count` — the sync hard-rejects a
  config/draft team-count mismatch, so this must read 10):

```sql
SELECT id, team_count, reception_format, passing_td_points, interception_points,
       qb_slots + rb_slots + wr_slots + te_slots + flex_slots
       + superflex_slots + bench_slots AS config_rounds
FROM league_config WHERE id = <CFG>;
-- expected: team_count=10, PPR, 4.00, -1.00, config_rounds=13
```

**Run B config:** use existing **id 4** (12-team, no superflex, 13 config
rounds — the closest shape mirror; only `team_count` is gated, scoring format
is irrelevant to Run B's criterion). Record it as Run B's `CFG`.

---

## Run A — 10-team quick-create bot draft (PRIMARY, criteria binding)

Mirrors the real league's shape. One draft ≈ 5 minutes. Use the 10-team
config created in Step 0.5 (record its id as `CFG`).

**A1. Link pre-draft.**
Create the draft in Sleeper, do NOT start it.

```bash
curl -s -X POST http://localhost:8080/api/sleeper/sync \
  -H "Content-Type: application/json" \
  -d '{"draftId":"'$DRAFT'","leagueConfigId":<CFG>}' > a1-link.json
```

- Expected: 202, `state=WATCHING`, `sessionId` null.
- psql: `SELECT count(*) FROM draft_session WHERE sleeper_draft_id='<DRAFT>';` → **0**.
  (Deferred creation holds: no row until `drafting`.)

**A2. Start the draft.**
- Expected: within 2 poll intervals (≤ 6s)
  `curl -s http://localhost:8080/api/sleeper/sync/$DRAFT > a2-status.json`
  shows `state=SYNCING`, `sessionId` non-null. Record `sessionId` → `SID`.
- psql the session row:

```sql
SELECT id, league_config_id, season, team_count, user_slot, total_rounds,
       status, sleeper_draft_id, created_at, updated_at
FROM draft_session WHERE sleeper_draft_id = '<DRAFT>';
```

  Expected values: `team_count=10`, `total_rounds=15` (draft object, not the
  13-round config sum), `season=2026`, `status=ACTIVE`,
  `sleeper_draft_id=<DRAFT>`, `created_at`/`updated_at` NON-NULL (the Step-0
  callback, verified live). `user_slot` cannot be predicted — record the slot
  the Sleeper app shows you, assert the DB value EQUALS it.
- Log: the disclosure line, exactly once:
  `config expresses 13 roster rounds; draft has 15 — K/DEF slots not modeled in config`.

**A3. Mid-draft consistency (pause before one of your picks, like the probe).**
- `curl -s https://api.sleeper.app/v1/draft/$DRAFT/picks > a3-picks-mid.json`;
  count its array: `jq length a3-picks-mid.json`.
- psql: `SELECT count(*), max(overall_pick_no) FROM draft_pick WHERE session_id=<SID>;`
- Expected: DB count == curl count == max pick_no (no gaps); status endpoint
  (`curl -s http://localhost:8080/api/sleeper/sync/$DRAFT`) `picksSynced` ==
  same number.
- Log grep for the cross-check: **zero** occurrences of "slot mismatch".

**A4. Kill-restart drill (the 4.2 DB-kill's sibling).**
Mid-draft, with picks still flowing: kill the app process. Let bots make ≥ 5
more picks. Restart the app. Re-POST the identical A1 body (same
`{draftId, leagueConfigId}` — relink).
- Expected: 202 (terminal/absent registry entry → restart legitimate);
  catch-up within 2 polls.
- psql: `SELECT count(*) = max(overall_pick_no) AS continuous FROM draft_pick
  WHERE session_id=<SID>;` → **t** (no gaps, no dupes — dupes are impossible by
  PK, so continuity is the whole assertion).
- Criterion: the picks made while the app was DOWN are all present.

**A5. Mid-draft agent probe (grounding criterion, not token criterion).**
Ask the agent (fresh session) a draft question against this live session:

```bash
curl -s -X POST http://localhost:8080/api/draft/sessions/$SID/advise \
  -H "Content-Type: application/json" \
  -d '{"message":"Who is on my roster so far, and who should I target with my next pick?"}' \
  > a5-advise.json
```

- Criterion: every cited pick/roster/board fact traces to synced DB state —
  the roster it describes matches `draft_pick` rows for your slot; no invented
  picks, no players cited as available who are already drafted. Judged on the
  answer a user acts on.
- Record the transcript verbatim in the artifacts dir. (Any ungrounded roster
  or breadth claim: record against the 2-specimen family — the 3rd graduates
  the grounding line per the standing rule.)

**A6. Single-writer guard, live.**
Manual recordPick while synced:

```bash
curl -si -X POST http://localhost:8080/api/draft/sessions/$SID/picks \
  -H "Content-Type: application/json" \
  -d '{"playerId":"4866"}' > a6-guard.txt
```

- Expected: **409**, ProblemDetail title "Draft Sync Conflict", detail contains
  "Sleeper-synced". (`-i` so the captured artifact carries the status line.)

**A7. Completion.**
Let the draft finish.
- Expected: loop self-stops; `state=COMPLETE` within 2 polls of the Sleeper app
  showing completion; session `status=COMPLETE` in psql.
- `curl -s https://api.sleeper.app/v1/draft/$DRAFT/picks > a7-picks-final.json`.
  Expected: 150 picks (`jq length`).
- psql vs curl, the byte-comparable set check:
  `SELECT count(*) FROM draft_pick WHERE session_id=<SID>;` → **150**;
  the DB's `player_id` set equals the curl's `player_id` set:

```bash
docker compose exec -T postgres psql -U readoption -d readoption -At \
  -c "SELECT player_id FROM draft_pick WHERE session_id=<SID> ORDER BY player_id;" \
  > a7-db-players.txt
jq -r '.[].player_id' a7-picks-final.json | sort > a7-curl-players.txt
diff a7-db-players.txt a7-curl-players.txt   # → empty
```

- K/DEF presence: `SELECT count(*) FROM draft_pick dp JOIN player p ON
  p.id=dp.player_id WHERE dp.session_id=<SID> AND p.position IN ('K','DEF');`
  → **20** (10 DEF team codes + 10 kicker ids).
- `SELECT count(*) FROM draft_pick WHERE session_id=<SID> AND picked_at IS NULL;`
  → **0**.

**A8. Disclosed gap (graded against disclosure, not failed as surprise).**
After K/DEF rounds, `unfilledSlots` bench accounting may read up to 2 low
(config cannot model K/DEF slots until 5.1). Inspect via
`curl -s http://localhost:8080/api/draft/sessions/$SID/state > a8-state.json`.
Expected and accepted; anything WORSE than 2-low, or any NPE/dropped-entry in
`DraftStateView` from a K/DEF pick, is a real failure.

---

## Run B — 12-team bot draft (SUPPLEMENTARY, single criterion)

Purpose: exercise `SnakeOrder` at a modulus no fixture covers. Uses the
existing 12-team confirmed `league_config` **id 4** (see Step 0.5 — v1's
"create via the Phase 3 confirm flow first" was based on a wrong DB
assumption; corrected in changelog). A failure here is a FINDING to
investigate, not an acceptance failure for 5.0.

- Create a 12-team quick-create mock draft, link with
  `{"draftId":"<DRAFT-B>","leagueConfigId":4}`, start, let it run to
  completion (no pausing needed).
- **The criterion:** zero cross-check halts across all 180 picks
  (log grep "slot mismatch" → empty; `state=COMPLETE`; count=180; K/DEF=24).

---

## Pass/fail bookkeeping

Each criterion reported closed-or-open with its artifact, never narratively.
A failed run stands recorded as FAIL against the frozen criteria; diagnosis and
criterion rewrites happen between runs, in writing, in this file's changelog.

## Changelog
- 2026-07-21: v1 frozen (pre-run).
- 2026-07-22: v1.1 (still pre-run, no criterion changed). Operational fill-in:
  request bodies, exact curl/psql commands, Conventions block, Step 0.5.
  Prerequisite corrections against the live DB: (1) Run A's "existing 10-team
  config" does not exist — all four `league_config` rows are 12-team, so the
  10-team config is created in Step 0.5 (the sync's team-count gate makes this
  mandatory, `DraftSyncService.createSession`); (2) Run B's 12-team config
  already exists (id 4) — no creation needed. Also fixed the artifacts-dir
  name (`phase-5.0-` → actual `phase-5-0-`), resolved the 5.0-a hash to
  5ebe86b, and pinned the A3/A7 picks URL to the Sleeper public API (the
  independent comparison source, distinct from our `/api/draft` endpoints).
