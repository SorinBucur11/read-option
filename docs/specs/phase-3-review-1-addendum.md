# Read Option — Phase 3 Review-1 Addendum (Commit 2 fixes)

**For:** Claude Code, executing in the `read-option` repo, after Commit 2 (the
`customization` package) is built but before Commit 3 (the full test suite).
**Read first:** root `CLAUDE.md`. Apply repo conventions by name (Persistable is being
*removed* here where it doesn't earn its keep — see Fix E; that is deliberate, not a
convention violation).
**Scope:** five targeted fixes from code review, plus focused tests that defend each fix.
This is **its own commit**, separate from Commit 2. Do **not** write the full Commit 3
suite yet — these fixes change the validation contract, so the comprehensive suite comes
after this is reviewed.

---

## Decisions locked (from review; build against these)

- **Fix A** — scoring spec numeric fields → `BigDecimal`; all three scoring point columns
  standardize at `NUMERIC(4,2)`.
- **Fix C** — `@NotNull` on `ParsedLeague.tactics` was already removed by the owner (tactics
  is `@Valid`, nullable). Do **not** re-add it; verify the null path instead.
- **Fixes B, D, E** — implement as specified below.

---

## Fix A — `BigDecimal` scoring inputs, consistent `NUMERIC(4,2)`

**Why:** real formats use fractional TD/INT points (e.g. −0.5/INT). `Integer` spec fields
forbid that while the columns implied decimals, and the three point columns had inconsistent
scales `(4,1)`/`(4,1)`/`(4,2)`. Standardize.

1. **`ScoringSpec`** — change `passingTdPoints` and `interceptionPoints` from `Integer` to
   `BigDecimal`. Swap `@Min/@Max` for `@DecimalMin/@DecimalMax`, same bounds:
   ```java
   @DecimalMin("3") @DecimalMax("8") BigDecimal passingTdPoints,     // null = preset default
   @DecimalMin("-6") @DecimalMax("0") BigDecimal interceptionPoints, // null = preset default
   ```
   Keep both nullable — `@DecimalMin/@DecimalMax` treat `null` as valid, so nullable-with-bounds
   works and `null` still means "use the preset default."
2. **`LeagueRulesResolver.resolve`** — drop the `new BigDecimal(x.toString())` bridge (the
   values are already `BigDecimal`); use them directly, falling back to the registry defaults
   when null. No `double` constructor anywhere.
3. **`V10__create_league_config_table.sql`** — set all three to `NUMERIC(4,2)`:
   `passing_td_points`, `interception_points`, `te_reception_bonus`.
   - **Migration hygiene:** V10 is the newest migration and `league_config` is a brand-new,
     empty, unshipped table. If V10 has **not** been applied yet (no app start since it was
     added → no V10 row in `flyway_schema_history`), **edit V10 in place.** If it **has** been
     applied locally, either add **V11** to `ALTER` the three columns, or (dev DB, empty table)
     reset: `DROP TABLE league_config`, delete the V10 row from `flyway_schema_history`, restart
     so V10 re-applies. Report which path you took.
4. **`LeagueConfig` entity** — fields are already `BigDecimal`; no change. `ddl-auto=validate`
   passes against `NUMERIC(4,2)`.

**Defend it:** a resolver test where `interceptionPoints = -0.5` round-trips through the
column unchanged (proves the scale actually holds a fraction).

---

## Fix B — bound `earliestRoundByPosition` as BLOCKING (the tactics validation hole)

**Why:** `earliestRoundByPosition` (`Map<Position, Integer>`) has no bound. `{QB: 0}`,
`{QB: 900}`, `{QB: -3}` pass silently and persist. It is the one tactics field with a
mechanical consumer (a Phase 4 draft gate reads the round), so a broken value is a structural
bug and must be **BLOCKING** — not the `ASSUMPTION` the `tactics.*` prefix rule would assign,
and with a value-bearing message the annotation route can't give.

1. **New class `validation/DraftTacticsValidator` (`@Component`)** — sibling to
   `LeagueRulesValidator`; the validation layer mirrors the parse-target authority split
   (rules validator for the engine-bound half, tactics validator for the strategy-bound half).
   ```java
   private static final int MIN_DRAFT_ROUND = 1;
   private static final int MAX_DRAFT_ROUND = 30;

   // Null-safe: null tactics OR null map => no issues.
   public List<ValidationIssue> validate(DraftTactics tactics) { ... }
   ```
   For each entry in `earliestRoundByPosition` (when the map is non-null): if the round is
   `null`, `< MIN_DRAFT_ROUND`, or `> MAX_DRAFT_ROUND`, add a **BLOCKING** `ValidationIssue`,
   field `"tactics.earliestRoundByPosition"`, message naming the position and the bad value
   (e.g. `"Earliest draft round for QB (0) must be between 1 and 30."`).
2. **Wire into `LeagueConfigService.validate(...)`** — after `rulesValidator`, append
   `tacticsValidator.validate(parsed.tactics())` to the merged issue list. `parsed.tactics()`
   may be null; the validator handles it.
3. **Do NOT** add `@Min/@Max` to the map type parameter — this field must be BLOCKING and needs
   a value-bearing message; the annotation route is mapped to ASSUMPTION by the prefix rule.
   The object validator owns this constraint (same as playoff bounds live in the object
   validator, not annotations).
4. **Optional stronger check — implement only if it stays clean:** a round cannot exceed total
   draft length = `qbSlots+rbSlots+wrSlots+teSlots+flexSlots+superflexSlots+benchSlots`. This
   is cross-field (needs `RosterSpec`); if you take it, pass `parsed.rules().roster()` too and
   emit a BLOCKING issue for a round beyond the draft. If it complicates the null-guarding,
   skip it and keep the flat 1..30 bound; note which you did.

**Defend it:** `DraftTacticsValidator` unit tests — valid `{QB:10}` → no issue; `{QB:0}`,
`{QB:40}`, `{QB:null}` → BLOCKING each; null tactics and null map → no issue. Plus one
service/controller test that a bad round yields `NEEDS_INPUT` and `confirm` refuses (no persist).

---

## Fix C — verify the nullable-tactics path (already partly done)

`@NotNull` was removed from `ParsedLeague.tactics` (now `@Valid` only). **Do not re-add it.**
A league with no stated tactics is legitimate. Verify no NPE anywhere on a tactics-free league:
- `LeagueConfigService.validate` — the new `DraftTacticsValidator` is null-safe (Fix B).
- `toEntity` — stores `parsed.tactics()`; null maps to a null `jsonb` column (fine).
- `RefineDriftGuard.diff` — already null-guards `before/after.tactics()`.

**Defend it:** a service test that a tactics-free `ParsedLeague` parses, validates, and
confirms, persisting a row with `tactics IS NULL`, no NPE.

---

## Fix D — externalize the parser system prompt

**Why:** the prompt lives in `application.properties` across ~18 backslash-continued lines. A
single stray trailing space silently truncates it, and it still passes `@NotBlank` — a silent,
wrong prompt. Move it to a file.

1. Create `src/main/resources/prompts/league-parser.txt` with the current prompt text as plain
   text (no line-continuation backslashes).
2. Remove `systemPrompt` from `CustomizationProperties` (the record) and from
   `application.properties`. Keep `model` and `maxRefineTurns`.
3. **`LeagueParsingService`** — inject the resource and read it in the constructor:
   ```java
   public LeagueParsingService(ChatClient.Builder builder,
                               CustomizationProperties properties,
                               ObjectMapper objectMapper,
                               @Value("classpath:prompts/league-parser.txt") Resource promptResource) {
       String systemPrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
       if (systemPrompt.isBlank()) {
           throw new IllegalStateException("league-parser system prompt is empty");  // fail fast at startup
       }
       this.chatClient = builder.defaultSystem(systemPrompt).build();
       ...
   }
   ```
   Fail-fast on empty so a missing/blank prompt breaks startup, not the first request.

**Defend it:** log the loaded prompt length once at startup; confirm it's the full text.

---

## Fix E — strip dead `Persistable` from `LeagueConfig`

**Why:** `league_config` uses `IDENTITY` generation and only ever inserts (one row per confirmed
config, no upsert). `Persistable` exists to defeat Spring Data's exists-check on **assigned**
ids; with `IDENTITY` the id is null pre-insert, so Spring Data already treats it as new.
`Persistable` here is machinery defending against a problem the id strategy already prevents.

Remove from `LeagueConfig`:
- `implements Persistable<Long>`
- the `isNew` field (+ its `@Builder.Default`), `markExisting()`, and the `isNew()` override
- the `@Override @JsonIgnore public Long getId()` override — keep a plain `@Id Long id` with
  Lombok `@Getter`

**Consequence to keep (a latent bug this fixes):** the removed `@JsonIgnore` on `getId()` was
hiding the id from JSON, so `confirm()`'s response body never returned the new id. After the
strip, `confirm` returns the created id. Good — that's the correct response.

Keep unchanged: `@Entity`/`@Table`, `@Getter`/`@Setter`/`@Builder`/`@NoArgsConstructor`/
`@AllArgsConstructor`, `FlexEligibleConverter`, `@JdbcTypeCode(SqlTypes.JSON)` tactics,
`@PrePersist`/`@PreUpdate`. `LeagueConfigRepository` stays `JpaRepository<LeagueConfig, Long>`.

**Defend it:** the persistence test asserts a save returns a non-null generated id and the
entity is retrievable by it (no upsert/idempotency assertion — this table only inserts).

---

## Self-review (run `/review` on the diff, then this checklist)

- **Three-type boundary intact:** the LLM emits `ParsedLeague` (spec) only; there is no field
  for the TE-bonus value; `TE_PREMIUM_BONUS` and the point defaults live only in
  `LeagueRulesResolver`; `confirm` resolves before persist (`toEntity` reads the **resolved**
  scoring, not the spec).
- **No LLM call inside `@Transactional`:** `confirm` is the only writer and contains no
  parse/refine call.
- **BigDecimal path:** no `double` constructor; `@DecimalMin/@DecimalMax` on the spec; all
  three scoring columns `NUMERIC(4,2)`; entity fields `BigDecimal`.
- **Tactics validator:** BLOCKING severity; null-safe on null tactics **and** null map;
  value-bearing message; wired into `validate()`; a round of `0`/`40`/`null` → `NEEDS_INPUT`,
  no persist.
- **Nullable tactics:** a tactics-free description parses → validates → confirms → persists
  (`tactics IS NULL`), no NPE.
- **Prompt:** loads from the classpath file, non-empty, startup fails fast if missing/blank;
  no prompt text left in `application.properties`.
- **Persistable removed:** no dangling `isNew`/`markExisting` references anywhere; `confirm`'s
  response includes the generated id.
- **Migration:** state whether V10 was edited in place or V11 added, and how the local DB was
  reset.

## Verify and report

- `./mvnw clean verify` green.
- Report back: which migration path (edit V10 vs V11 vs reset); the `DraftTacticsValidator`
  test cases and outcomes; confirmation the `-0.5` interception round-trips through
  `NUMERIC(4,2)`; the loaded prompt length; and that a tactics-free `confirm` persists cleanly
  with `tactics IS NULL`.

## Scope fence

- Do **not** write the full Commit 3 suite — only the focused tests that defend these five
  fixes.
- Keep this as its own commit (review fixes), separate from Commit 2.
