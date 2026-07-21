# Phase 5.0-a — Fixture provenance fix (spec)

**Owner:** Sorin. **Executor:** Claude Code. **Scope:** small, single-commit.
**Context:** review finding N2 on the Phase 5.0 diff. The two draft-object test
fixtures (`draft-pre_draft.json`, `draft-drafting.json`) are executor-authored
reconstructions of Sleeper payloads that were never committed. Field-diffing them
against the real captured payloads showed the reconstruction got the wire wrong:
it invented three settings fields the real response doesn't carry (`alpha_sort`,
`nomination_timer`, `autopause_enabled`), omitted one it does (`slot_to_roster_id`,
present pre-draft as an identity map), and fabricated the draft id. Fixtures are
the pinned record of the wire contract — a reconstruction encodes assumptions as
evidence. This increment replaces them with the real payloads and removes the
duplicate-fact coupling the swap exposes in the tests.

**Provenance note for both real payloads below:** content-faithful, not
byte-faithful — the original curl output passed through pretty-printing before
being recorded, so the field set and values are exact but byte formatting is
reconstructed. Say exactly this in the README.

---

## Investigation first (report before changing anything)

1. Diff the current `src/test/resources/sleeper/draft-pre_draft.json` and
   `draft-drafting.json` against the real payloads below. Report the field-level
   differences you find (expected: the four listed above; flag anything beyond).
2. Identify every test in `DraftSyncServiceTest` whose stubs are keyed on the
   `DRAFT_ID` constant while the draft object under test comes from a FIXTURE
   file. These are the tests the swap would break (stub-miss → NPE on
   `Optional`). Report the list. `DraftSyncRunnerTest` builds its drafts inline
   and must need NO change — confirm that.

## Then the fix

### New files (probe record)

`docs/specs/phase-5.0-probes/p7-draft-pre_draft.json` — exactly:

```json
{"created":1783956532382,"creators":["87732859926102016"],"draft_id":"1382417529397342208","draft_order":null,"last_message_id":"1382417529397342208","last_message_time":1783956532382,"last_picked":null,"league_id":null,"metadata":{"description":"","name":"","scoring_type":"std"},"season":"2026","season_type":"regular","settings":{"autostart":0,"cpu_autopick":1,"pick_timer":120,"rounds":15,"slots_def":1,"slots_flex":2,"slots_k":1,"slots_qb":1,"slots_rb":2,"slots_te":1,"slots_wr":2,"teams":10},"slot_to_roster_id":{"1":1,"10":10,"2":2,"3":3,"4":4,"5":5,"6":6,"7":7,"8":8,"9":9},"sport":"nfl","start_time":null,"status":"pre_draft","type":"snake"}
```

`docs/specs/phase-5.0-probes/p8-draft-drafting.json` — exactly:

```json
{"created":1783956532382,"creators":["87732859926102016"],"draft_id":"1382417529397342208","draft_order":{"87732859926102016":7},"last_message_id":"1382417640307306496","last_message_time":1783956558829,"last_picked":1783956610346,"league_id":null,"metadata":{"description":"","name":"","scoring_type":"std"},"season":"2026","season_type":"regular","settings":{"autostart":0,"cpu_autopick":1,"pick_timer":120,"rounds":15,"slots_def":1,"slots_flex":2,"slots_k":1,"slots_qb":1,"slots_rb":2,"slots_te":1,"slots_wr":2,"teams":10},"slot_to_roster_id":{"1":1,"10":10,"2":2,"3":3,"4":4,"5":5,"6":6,"7":7,"8":8,"9":9},"sport":"nfl","start_time":1783956558819,"status":"drafting","type":"snake"}
```

### Modified files

| File | Change |
|---|---|
| `src/test/resources/sleeper/draft-pre_draft.json` | overwrite with p7 content verbatim |
| `src/test/resources/sleeper/draft-drafting.json` | overwrite with p8 content verbatim |
| `src/test/resources/sleeper/README.md` | delete the "Executor-authored" section; record both files as content-faithful copies of p7/p8 with the provenance note above |
| `src/test/java/app/readoption/draft/DraftSyncServiceTest.java` | see below |

### `DraftSyncServiceTest` — derive, don't duplicate

For every test identified in investigation step 2: derive the draft id FROM the
loaded fixture instead of the `DRAFT_ID` constant, the way
`oneShotImportFullDraft` already does (`leagueDraft.draftId()`):

```java
SleeperDraft drafting = draftingDraft();
String draftId = drafting.draftId();
when(client.fetchDraft(draftId)).thenReturn(drafting);
when(sessionRepository.findBySleeperDraftId(draftId)).thenReturn(Optional.empty());
// ...
service().pollOnce(draftId, CONFIG_ID, USER_ID);
```

Rationale (put a one-line comment where the pattern is introduced): the id
inside a fixture and a constant naming it are the same fact stored twice; a fact
stored twice will eventually disagree. Keep `DRAFT_ID` only for tests that build
draft objects inline (where it remains the single copy). Do NOT change assertion
semantics anywhere — this is a keying refactor, not a behavior change.

`draftingDraft()`'s fixture now carries `slot_to_roster_id` and `metadata` —
both unmapped by `SleeperDraft`; `ignoreUnknown` must absorb them. If ANY test
fails on the real bytes for any other reason, STOP and report the failure —
that break is a finding, not something to patch.

Note: the drafting fixture's `scoring_type` changes `"ppr"` → `"std"` and the
draft is league-less. Nothing in 5.0 consumes either fact; if a test turns out
to depend on one, that is a stop-and-report finding too.

---

## Constraints

- ONE commit, one-line message. Only the files named above; anything else
  needed → stop and flag with reasoning BEFORE writing.
- No production-code changes of any kind. `DraftSyncRunnerTest` untouched.
- Full suite green (383) at the end; report the count.
- End with a senior-developer review of your own diff.
