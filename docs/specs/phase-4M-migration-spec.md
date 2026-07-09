# Phase 4.M — Spring Boot 4 + Spring AI 2.0 Migration Spec

**Status:** ready for execution
**Executor:** Claude Code (implementation), Sorin (review of diff + acceptance runbook)
**Baseline:** Java 21 already committed (Commit 1); `mvn verify` green on jdk-21.0.10; 310 tests.
**Baseline artifacts:** `docs/specs/phase-4M-baseline/` (captured 2026-07-09 on Boot 3.5.14 / Spring AI 1.1.8)

---

## 0. Why this increment exists (context for the executor)

Spring Boot 3.5 / Framework 6.2 reached EOL 2026-06-30. Spring AI 2.0.0 GA (2026-06-12)
requires Boot 4 / Framework 7 and reshapes the tool-execution and vector-store APIs that
Phase 4.4 will be written against. The upgrade is **atomic by construction**: Spring AI
1.1.x cannot run on Boot 4; Spring AI 2.0 cannot run on Boot 3.5. Therefore one
coordinated migration commit window, spec-driven.

**Scope discipline:** this increment changes versions and adapts code to compile and
behave identically. NO feature work, NO refactors beyond what the new APIs force, NO new
Flyway migrations. Anything tempting but optional goes into the findings ledger as
DEFERRED, not into the diff.

---

## 1. Required reading before any edit

1. Spring AI 1.1.x → 2.0.0 upgrade notes (linked from the v2.0.0 GitHub release).
2. Spring Boot 3.5 → 4.0 release notes / migration guide (wiki), including the
   Jackson 3 migration section.
3. Skim the Spring AI 2.0.0-RC1 + GA blog posts for the tool-execution consolidation
   (`internalToolExecutionEnabled` removal; external execution via ToolCallingAdvisor or
   user-controlled `DefaultToolCallingManager` loop).

Do not migrate from memory of 1.x APIs. Where this spec and the official upgrade notes
disagree, the upgrade notes win — flag the divergence in the findings ledger.

---

## 2. Version changes (pom.xml)

| Coordinate | From | To |
|---|---|---|
| `spring-boot-starter-parent` | 3.5.14 | latest 4.0.x GA at execution time (check Maven Central; 4.0.5+ known to exist) |
| `spring-ai.version` (BOM) | 1.1.8 | 2.0.0 |
| `java.version` | 21 | 21 (unchanged — done in Commit 1) |

Verification items while touching the pom:

- **V-1:** Confirm the Anthropic starter artifactId is unchanged in 2.0
  (`spring-ai-starter-model-anthropic`). The 2.0 GA notes state Anthropic support now
  leverages the official vendor SDK — artifact names, transitive deps, and options
  classes may have moved. Check the 2.0 BOM contents; adjust the dependency if renamed.
- **V-2:** `flyway-core` + `flyway-database-postgresql` versions are Boot-parent-managed;
  confirm Boot 4 still manages them (it manages Flyway 11.x). Remove nothing; version
  numbers stay unmanaged in our pom.
- **V-3:** `commons-csv` 1.12.0 is ours (not Boot-managed) — leave as is.
- **V-4:** Temporarily add `spring-boot-properties-migrator` (scope runtime) to surface
  renamed/removed configuration properties at startup. **Remove it before the final
  commit** — record any property renames it reports in the findings ledger and fix them
  in `application.properties`/`application.yml`.

---

## 3. Jackson 3 sweep

Boot 4 moves to Jackson 3. Two distinct blast zones:

**3a. Compile-time (loud):** the databind/core packages moved
`com.fasterxml.jackson.*` → `tools.jackson.*`, and `ObjectMapper` direct use is replaced
by `JsonMapper` idioms. Search the codebase for `com.fasterxml.jackson` imports:

- Raw sync mappers that serialize API responses into `source_payload` JSONB.
- Any `ObjectMapper` injection or `new ObjectMapper()` (tests included).
- The `flex_eligible` CSV converter and any `AttributeConverter` using Jackson.

**IMPORTANT nuance:** the **annotations** package did NOT move — `jackson-annotations`
keeps `com.fasterxml.jackson.annotation.*` in Jackson 3. `@JsonInclude`, `@JsonProperty`,
`@JsonIgnore` imports stay untouched. Do not "fix" annotation imports; if the compiler is
happy with them, they are correct.

**3b. Runtime/behavioral (silent):** Jackson 3 changed serialization defaults (notably
date/time handling and some numeric/ordering defaults). Nothing here fails a build; it
changes bytes in JSON output. This is what the baseline diff exists for (§7). Areas of
exposure, in risk order:

1. `source_payload` writes (next sync writes through Jackson 3).
2. View serialization: `DraftStateView`, `PlayerProfileView` (incl. nested-record
   `@JsonInclude(NON_NULL)` behavior), board response — watch **BigDecimal scale
   preservation** (baseline shows `416.40` with trailing zero preserved; that must hold).
3. RFC 9457 `ProblemDetail` output from `GlobalExceptionHandler`.
4. `BeanOutputConverter` targets (`Verdict`, `ParsedLeague`) — schema generation and
   deserialization both ride Jackson.

---

## 4. Spring AI 2.0 API migration

**4a. The agent loop (`DraftAgentService`).** In 2.0, ChatModels no longer execute tools
internally at all — `internalToolExecutionEnabled` is removed because external execution
is now the only mode. Our manual loop (ChatModel + ToolCallingManager, we own the while)
is the documented "user-controlled DefaultToolCallingManager loop" pattern. Migration:

- Remove the `internalToolExecutionEnabled(false)` option from the Anthropic options
  build. It has no replacement; its absence is the new default.
- Verify `ToolCallingManager` / `DefaultToolCallingManager` API surface against 2.0
  javadoc (method names for executing tool calls and extracting tool responses). Adapt
  signatures only; loop structure, iteration cap (`AgentLoopLimitException`), DEBUG
  instrumentation, and no-transaction-around-LLM-calls all stay byte-for-byte in intent.
- Do NOT adopt `ToolCallingAdvisor` in this increment. Re-evaluating advisor-based
  execution vs the manual loop is a design question for a later chat session, not a
  migration task. Record as DEFERRED.

**4b. Options builders.** Setter methods were removed from all provider options classes
across the 2.0 milestones. Any `setX(...)` on `AnthropicChatOptions` (or whatever the
vendor-SDK-backed options class is now named — see V-1) becomes
`Builder`-pattern construction. Affected sites: agent config, reconciliation
(`VerdictClassifier` model/options wiring), customization parser wiring.

**4c. `@Tool` / tool schema.** Verify the `@Tool` / `@ToolParam` annotations and the
tool-callback construction used by `DraftAgentTools` (per-request POJO, five methods)
survive unchanged or adapt imports. The **schema-parsing safety test** (five tools,
exactly their documented params, no sessionId/rules leakage) is the acceptance gate here
— if it passes unmodified, the schema surface survived; if it needs edits, every edit
must be justified in the findings ledger.

**4d. ChatMemory.** `MessageWindowChatMemory` used directly (not via advisor). Verify
class location/constructor in 2.0; the conversationId-explicit changes in 2.0 mostly
affect advisors, but confirm our direct usage compiles and the memory-window semantics
tests still pass.

**4e. `BeanOutputConverter`.** Confirm it exists in 2.0 (structured output was reworked
around `ChatClient.entity()` + `EntityParamSpec`). If `BeanOutputConverter` survives:
keep it, minimal change. If deprecated/removed: migrate `VerdictClassifier` and the
league parser to the nearest 2.0 equivalent that preserves the type boundary (the model
still emits ONLY the narrow spec/enum — no API change may widen what the model can
write). Flag whichever path was taken.

---

## 5. Boot 4 / Framework 7 / Hibernate 7 residue

- **Hibernate ORM 7 (Jakarta Persistence 3.2):** Boot 4 manages Hibernate 7. Our
  exposure: `@IdClass` composite keys, `Persistable` + `@Builder.Default` isNew pattern,
  dirty-checking COMPLETE flip, bulk JPQL `@Modifying(flushAutomatically,
  clearAutomatically)` delete-then-insert flush ordering, constraint-name extraction via
  `ConstraintViolationException.getConstraintName()`. ALL of these are pinned by
  existing integration tests on the real pgvector container — the test suite is the
  detector. Change no persistence code preemptively; fix only what red tests demand,
  one finding per fix.
- **`ddl-auto=validate` + Flyway 11:** app must start clean against the existing V1–V14
  schema history. `flyway validate` semantics must not change history. NO new migrations.
- **Test annotations:** `@MockitoBean` already in use (3.4+ rename) — verify no
  remaining `@MockBean` stragglers; Boot 4 removes the deprecated form.
- **Property renames:** whatever `spring-boot-properties-migrator` reports (V-4).
- **Actuator / web auto-config changes:** Boot 4 modularized auto-configuration; if any
  bean fails to materialize at startup, record the auto-config change that caused it
  and the minimal fix.

---

## 6. Commit plan

- **Commit 2 (this spec's core):** pom versions + every change §3–§5 forces, suite
  green. Atomic because the framework coupling makes it so.
- **Commit 3 (only if needed):** `spring-boot-properties-migrator` removal + property
  renames, if not folded into Commit 2.
- Optional cleanups discovered along the way: DEFERRED to the ledger, own commits later,
  never mixed in. (Standing rule: correctness and standardization never share a commit.)

---

## 7. Acceptance runbook (Sorin executes, evidence per item)

**R-1. Build & tests:** `mvn verify` → 310 green on Boot 4 / Spring AI 2.0 / Java 21.

**R-2. Anchors:** Barkley 208.50 / 226.00 / 243.50 and Mahomes ×−2 INT byte-identical
(implied by R-1 since they are asserted in-suite; state explicitly in the ledger).

**R-3. Startup:** app boots against the existing DB; Flyway validates V1–V14 with no new
entries; `ddl-auto=validate` passes; zero property-migrator warnings remaining.

**R-4. JSON diffs (deterministic surface):** re-curl the four baseline endpoints
(state, board, profile, 404 ProblemDetail) and diff against
`docs/specs/phase-4M-baseline/`. Expected: byte-identical or explainable-and-accepted
(each accepted diff documented — e.g. a Jackson 3 default we choose to keep). Watch
BigDecimal scale (`416.40` stays `416.40`).

**R-5. source_payload diff:** re-run the two sync jobs, re-run the identical psql dumps
(same deterministic `ORDER BY player_id LIMIT 1` rows: rotowire 10210, espn 10213), diff
value shapes. jsonb key order is normalized by Postgres — ignore ordering, inspect
values.

**R-6. Agent behavioral equivalence (probabilistic surface):** run the baseline prompt
twice against frozen session 4. Pass criteria, set from the measured baseline
(runs: 2 iterations each; tokens 10,955 and 13,308; tool set identical):

- Tool SET equals baseline: {getDraftState, getDraftBoard, getPlayerProfile×4 —
  same four playerIds} (order/args may vary; `limit` variation is noise).
- Iterations ≤ 3 (baseline: 2).
- Total tokens within ~9,000–17,000 (baseline band ±25%).
- Advice cites VORP/ADP matching the live board endpoint to the digit — the
  never-originates-a-number invariant.
- No new degradation strings, no fabricated facts vs the tool results in the DEBUG log.

Byte-identical advice text is NOT required and NOT expected.

**R-7. Findings ledger:** every V-item, every forced code change, every accepted diff —
closed-or-open with diff/log evidence, checklist form, no narrative reporting.

---

## 8. Rollback

Single revert of the migration commit(s) restores Boot 3.5.14 / Spring AI 1.1.8. The DB
is untouched by design (no schema changes, no destructive syncs required for
acceptance — R-5 syncs are upserts into landing tables). If R-5 must be run before a
rollback decision, the raw landing rows rewritten by it are re-derivable from the source
APIs; the mart is untouched unless reconciliation is run (do not run reconciliation
during acceptance).

---

## 9. Explicitly out of scope (pre-answered temptations)

- pgvector starter / EmbeddingModel / VectorStore (Phase 4.4 — note for later: known
  2.0 issue, pgvector starter requires explicit `spring-boot-starter-jdbc`).
- ToolCallingAdvisor adoption / agent loop redesign (design session first).
- SleeperClient → RestClient standardization (own increment).
- Virtual-threads enablement (candidate for the reconciliation concurrency pass, later).
- JDBC-backed ChatMemory, record_pick tool, frontend — unchanged deferrals.
