# Phase 5 — Draft-Night Runbook

The operator's manual for running Read Option against a **live Sleeper draft**
(Phase 5.0 sync), with the fully **manual session** as the fallback path. This
is the "how do I actually use it on draft night" document — the acceptance
criteria and their evidence live in
`docs/specs/phase-5-0-acceptance/phase-5.0-acceptance-runbook-v1.md`.

How it works in one paragraph: you link a Sleeper draft to a confirmed league
config; a background loop polls Sleeper every 3 seconds; the moment the draft
starts, a draft session is created from the draft's own facts (teams, rounds,
your slot) and every pick — yours and opponents' — is mirrored into the pick
ledger automatically. You never record a pick by hand; you just ask the agent
for advice while on the clock. The session is marked COMPLETE only when the
pick count honestly equals `teams × rounds`.

---

## 0. Prerequisites (do these BEFORE draft night)

**Environment.**

- `ANTHROPIC_API_KEY` set (league parsing + draft advice).
- `SPRING_AI_OPENAI_API_KEY` set (the app does not boot without it).
- `readoption.sleeper.username=<your-sleeper-username>` set **locally** in
  `application.properties`. It is deliberately blank in the repo — the app
  boots without it and a sync start fails fast with a 400. **Never commit this
  edit.** Your Sleeper user must be a participant in the draft (present in its
  `draft_order`).

**Services.**

```bash
docker compose up -d          # PostgreSQL + pgvector on host port 5433
./mvnw spring-boot:run        # app on :8080, Flyway migrates on boot
```

**Data.** The board and agent are only as good as the loaded data. Run the full
ETL + reconcile + context chain from the README ("Loading data") if you haven't
recently: players → ESPN ids → historical stats → both projection sources →
reconcile → schedule/byes → news sync → news embed. Sanity probe:

```bash
curl -s "http://localhost:8080/api/scoring/leaderboard/ranked?season=2026&format=PPR_4PT&page=0&size=5"
```

**League config.** The sync hard-rejects a config whose `teamCount` doesn't
match the Sleeper draft's team count, so confirm a config that matches the
league you're drafting in (one confirm per league shape; reuse the id every
year the shape holds). Describe the league in plain English:

```bash
curl -s -X POST http://localhost:8080/api/league/parse \
  -H "Content-Type: application/json" \
  -d '{"description":"10-team PPR league. Starting lineup: 1 QB, 2 RB, 2 WR, 1 TE, 2 flex (RB, WR or TE), 5 bench spots. Passing touchdowns are 4 points, interceptions are -1. No TE premium."}' \
  > parse.json

# status must be READY; if NEEDS_INPUT, POST /api/league/refine with
# {"current": <.parsed>, "correction": "...", "turn": 1} until it is
jq '{current: .parsed}' parse.json | curl -s -X POST \
  http://localhost:8080/api/league/confirm \
  -H "Content-Type: application/json" -d @- | jq .id
```

Record the returned id — it is `CFG` below. (The 2026 acceptance drafts used
config **5** for the 10-team quick-create shape, **4** for 12-team.)

Known modeling gap until 5.1: the config has no K/DEF slots, so a Sleeper
draft with K + DEF rounds has 2 more rounds than the config expresses. This is
fine — `totalRounds` comes from the draft object, and the sync logs one
disclosure line about it — but `unfilledSlots` in the state view can read up to
2 low after the K/DEF rounds.

---

## 1. Find the draft id

From the Sleeper app's draft URL, or by listing your drafts:

```bash
# your user id, once:
curl -s https://api.sleeper.app/v1/user/<your-username> | jq -r .user_id

# this season's drafts, newest first:
curl -s https://api.sleeper.app/v1/user/<user-id>/drafts/nfl/2026 | jq '.[0].draft_id'
```

This is `DRAFT` below. Link **before** the draft starts (recommended — you get
the WATCHING warm-up and instant first-pick sync), but mid-draft linking and
even post-draft linking work too: the set-difference sweep catches up whatever
already happened, and linking an already-complete draft is a one-shot import.

## 2. Link

```bash
curl -s -X POST http://localhost:8080/api/sleeper/sync \
  -H "Content-Type: application/json" \
  -d '{"draftId":"'$DRAFT'","leagueConfigId":CFG}'
```

Returns **202** with `state=WATCHING` and `sessionId=null` — the session is
deliberately NOT created until Sleeper reports the draft `drafting` (before
that, the draft object doesn't yet know your slot). Poll cadence is 3s
(`readoption.sleeper.sync.poll-interval`).

## 3. Monitor

```bash
curl -s http://localhost:8080/api/sleeper/sync/$DRAFT
# {draftId, state, sessionId, picksSynced, lastPollAt, error}
```

Lifecycle: `WATCHING` (pre-draft) → `SYNCING` (drafting; `sessionId` appears
within ~2 polls of the start) → `COMPLETE`. `STOPPED` (you called stop) and
`ERROR` (see troubleshooting) are the other terminal states. Once `sessionId`
is non-null, record it — it is `SID` for the state/board/advise endpoints.

Log lines worth knowing: one
`config expresses N roster rounds; draft has M — K/DEF slots not modeled in config`
disclosure at session creation, and a
`draft {}: synced X new pick(s), Y total` line per poll that lands picks.

## 4. On the clock

Picks mirror automatically — **do not** record picks by hand; the manual pick
endpoint answers **409 "Draft Sync Conflict"** on a Sleeper-synced session
(single-writer by prevention; the poll loop is the only writer).

```bash
# live state: current pick, your roster + byes, picks until your turn
curl -s http://localhost:8080/api/draft/sessions/$SID/state

# VORP board of available players under YOUR league's rules
curl -s "http://localhost:8080/api/draft/sessions/$SID/board?limit=20"
curl -s "http://localhost:8080/api/draft/sessions/$SID/board?position=RB&limit=10"

# the agent (live model call; session-scoped memory recalls prior turns)
curl -s -X POST http://localhost:8080/api/draft/sessions/$SID/advise \
  -H "Content-Type: application/json" \
  -d '{"message":"I am on the clock in 3 picks. Who should I target and who is my backup if he goes?"}'
```

The agent reads the same synced ledger, so its roster/availability facts are
current to the last poll (≤3s behind Sleeper).

## 5. Completion

When Sleeper reports the draft complete, the loop runs a final sweep and ends
**only if** the persisted count equals `teams × rounds` — Sleeper is known to
flip `complete` before its picks array finishes settling (the Run B incident:
166/168). On a short count the loop logs
`draft {} complete at Sleeper but picks array holds N/M — continuing to poll`
and keeps polling under a grace budget of 20 polls (~60s,
`readoption.sleeper.sync.completion-grace-polls`). Settles → COMPLETE as
normal. Never settles → `ERROR` with
`...picks settled at N/M after 20 grace polls — relink to retry`.

Optional post-draft check:

```bash
curl -s https://api.sleeper.app/v1/draft/$DRAFT/picks | jq length   # == teams x rounds
curl -s http://localhost:8080/api/draft/sessions/$SID/state | jq .  # roster complete
```

## 6. Stopping (rarely needed)

```bash
curl -s -X POST http://localhost:8080/api/sleeper/sync/$DRAFT/stop
```

Cooperative stop; the session and its picks stay as they are. Relink any time
to resume — a terminal registry entry (COMPLETE/STOPPED/ERROR) never blocks a
new start.

---

## Troubleshooting

**The universal recovery move is the relink**: re-POST the exact link body
from step 2. The sync finds the existing session by `sleeper_draft_id`, skips
the creation gates, and the set-difference sweep inserts exactly the picks the
DB is missing — idempotent, no duplicates possible (composite PK), safe to do
any number of times, mid-draft or after.

| Symptom | Meaning | Action |
|---|---|---|
| 400 `readoption.sleeper.username is not set` | local property missing | set it, restart, relink |
| 400 `cannot start Sleeper sync: ...` | bad draft id, or Sleeper unreachable | check the id / connectivity, retry |
| 409 `a sync is already running for draft ...` | non-terminal loop exists | it's already working; `stop` first if you really want to restart |
| 409 `Draft Sync Conflict` on `POST .../picks` | manual pick against a synced session | expected — don't record picks by hand |
| ERROR: `only snake drafts are supported` / `3RR drafts unsupported` | auction or 3rd-round-reversal draft | unsupported in 5.0 — use the manual fallback below |
| ERROR: `linked user ... is not in this draft` | your username isn't a participant | fix the username / join the draft, relink |
| ERROR: `league config N has teamCount=X but ... teams=Y` | config/draft shape mismatch | confirm a matching config, relink with its id |
| ERROR: `slot mismatch — traded pick?` | Sleeper's slot/round disagrees with snake arithmetic | traded picks aren't modeled in 5.0; halt is deliberate (silent misattribution would poison every roster view) |
| ERROR: `is_keeper=...` | keeper league variant never observed | deliberate halt — flag it, don't guess |
| ERROR: error budget exhausted (5 consecutive failures) | sustained transport/DB failure | fix connectivity, relink — catch-up recovers everything missed |
| ERROR: `picks settled at N/M after 20 grace polls` | Sleeper's array never reached teams×rounds | relink (fresh grace budget); if it persists, compare the Sleeper picks endpoint count by hand |
| App crashed mid-draft | loop died with it (in-memory registry) | restart the app, relink — picks made while down are recovered by the sweep (kill-drill verified) |
| `unfilledSlots` looks 2 low late in the draft | K/DEF not modeled until 5.1 | known disclosed gap, ignore |

## Manual fallback (no Sleeper, or unsupported draft type)

The Phase 4 manual path still works and is the fallback for in-person drafts,
auction/3RR formats, or a dead sync you can't revive on the clock:

```bash
# start a session yourself (userSlot = your draft position)
curl -s -X POST http://localhost:8080/api/draft/sessions \
  -H "Content-Type: application/json" \
  -d '{"leagueConfigId":CFG,"userSlot":7}'

# record EVERY pick in draft order, yours and opponents' (server assigns
# the pick number; snake round/team are derived)
curl -s -X POST http://localhost:8080/api/draft/sessions/$SID/picks \
  -H "Content-Type: application/json" -d '{"playerId":"4866"}'
```

State, board, and advise work identically on a manual session. Note the two
paths don't mix: a session is either Sleeper-synced (manual picks 409) or
manual (no sync attached) for its whole life.

## Knobs

| Property | Default | Meaning |
|---|---|---|
| `readoption.sleeper.username` | *(blank)* | your Sleeper username; blank = sync start refuses with 400 |
| `readoption.sleeper.sync.poll-interval` | `PT3S` | sleep between polls of a watched draft |
| `readoption.sleeper.sync.error-budget` | `5` | consecutive transport/DB poll failures before ERROR (validation halts ignore it — they ERROR immediately) |
| `readoption.sleeper.sync.completion-grace-polls` | `20` | short-count polls tolerated after Sleeper says complete, before ERROR (~60s at PT3S) |
