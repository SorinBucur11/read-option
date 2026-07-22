# Phase 4.4 — Vector News RAG: Ingestion, Embedding Store, and `searchPlayerNews`

**Status:** Spec ratified. Decisions D1–D3 accepted (chat session 2026-07-10/11).
**Executor:** Claude Code. Commits ONLY the files named in each commit boundary below — explicit paths, never `git add .`.
**Reviewer:** Sorin — diff review per finding checklist; acceptance criteria pre-written in §9 before any build.

---

## 1. Context and goal

The 4.2 starvation transcripts identified the unstructured gap: the agent retrieves roles, depth charts, injuries, and schedule (4.3/4.3.1) but cannot explain **why** — the trade, the signing, the recovery timeline, the coaching change. 4.4 lands that half: an ESPN-sourced news corpus in PostgreSQL, embedded into pgvector, retrieved by a new `searchPlayerNews` tool.

The grounding invariant extends into the time dimension (audit finding A-4): **every cited news fact carries its publication date.** A stale truth cited as current is degradation toward plausible — the failure class this project designs against.

**Calendar constraint:** training camp opens late July 2026. Per audit finding A-5, high-coverage players retain as few as 4–5 blurbs in the source feed; camp-season blurb volume can cycle that buffer within a week, and rolled-off items are unrecoverable (A-3). **Commit B (ingestion) ships before Commits C/D are polished.** Every camp week without a running sync is star-player news permanently lost.

## 2. Ratified decisions (evidence in `phase-4-4-source-audit/`)

| # | Decision | Basis |
|---|----------|-------|
| D0 | Source: ESPN player-news endpoint, `type == "Rotowire"` items only | Audit ledger A-1..A-7 closed; player-keyed by espn_id; pre-chunked 300–700-char blurbs; stable numeric `id` for dedup; free; same unofficial-API risk class as existing EspnClient |
| D1 | Landing/derived split: `player_news` (raw, insert-only, no vector) + `news_embedding` (derived, rebuildable) | Version derived tables, not source-of-truth tables. Retention at the source is opaque (A-3) → the landing table is the only permanent record. Vector dimension is DDL; model identifier keys the derived rows |
| D2 | Embedding: OpenAI `text-embedding-3-small`, 1536 dims, invoked inside `PgVectorStore.add()` as a separate retryable batch step downstream of ingestion. Test seam: deterministic fake `EmbeddingModel` (1536-dim), same pattern as the mocked `ChatModel` | Cost ≈ $0.05 full-corpus; multi-provider architecture is a project goal; D1 makes provider swap = re-embed + config flip. Vendor failure (429/outage) delays the derived build only — never touches ingestion |
| D3 | Adopt `PgVectorStore` with `initializeSchema=false`; Flyway owns DDL; `vectorTableValidationsEnabled=true`; dimensions explicit (1536); deterministic UUID = `nameUUIDFromBytes(source + newsId + embeddingModelTag)` | Bytecode-verified (spring-ai-pgvector-store 2.0.0): validator checks column names + vector dimensions via `pg_attribute.atttypmod`, never column types → jsonb metadata passes (V-1 closed). Validator becomes a startup drift guard (V-2). Deterministic UUID ⇒ idempotent upsert + coexisting model generations |

## 3. Pre-work (Commit A)

1. **espn_id crosswalk repair.** One repair pass over the 8 draftable players with null `espn_id`, against the DynastyProcess `db_playerids.csv`. Report per player: repaired or unresolvable.
2. **Degradation vocabulary.** Players still lacking `espn_id` after repair degrade loudly: the news layer returns `NEWS_UNAVAILABLE_NO_ESPN_ID` (constant lives with the news service, mirroring `TeamContextService` vocabulary). Empty search results for a valid player return `NO_NEWS_FOUND`. Never empty-list-silence.

Out of scope, explicitly: the `draft_year`/`years_in_league` schema gap stays deferred — the news layer does not need it; it gets its own increment.

## 4. Dependencies (Commit C pom changes)

```xml
<!-- Vector store: CORE artifact + explicit @Bean. Deliberately NOT the starter:
     we own the bean construction (initializeSchema=false, validation on,
     dimensions pinned) and skip the autoconfig surface. -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store</artifactId>
</dependency>

<!-- OpenAI embeddings via starter -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

**Executor verification items (jars outrank docs outrank this spec — verify at build, report in review):**
- **EV-1:** Two model providers on the classpath (Anthropic chat + OpenAI). Set `spring.ai.model.chat=anthropic` and `spring.ai.model.embedding=openai` so autoconfiguration selects unambiguously. Verify the context boots and `DraftAgentService` still receives the Anthropic `ChatModel`; verify exactly one `EmbeddingModel` bean (OpenAI). If the property names differ in 2.0, resolve from the autoconfigure jar and report the actual names.
- **EV-2:** `PgVectorStore` needs a `JdbcTemplate`. `spring-boot-starter-data-jpa` has historically pulled `spring-boot-starter-jdbc`; Boot 4's modularization may have changed this (it moved RestClient out of webmvc). If no `JdbcTemplate` bean, add `spring-boot-starter-jdbc` explicitly — this is the known pre-parked wart.
- **EV-3:** `Document` construction API in Spring AI 2.0 (builder vs constructor, `.text()` vs `.content()`): resolve from the jar, use the current form.
- **EV-4:** OpenAI embedding model property: expected `spring.ai.openai.embedding.options.model=text-embedding-3-small`; verify against the autoconfigure jar. API key via `SPRING_AI_OPENAI_API_KEY` env var — never in properties files, never committed.

## 5. Migrations

**V15 (Commit B) — landing table:**

```sql
CREATE TABLE player_news (
    source          TEXT        NOT NULL,           -- 'espn'
    news_id         TEXT        NOT NULL,           -- ESPN item id, verbatim
    player_id       TEXT        NOT NULL,           -- Sleeper player_id (our canonical key; NO FK per landing-table convention)
    espn_player_id  BIGINT      NOT NULL,           -- the id the item was fetched under
    headline        TEXT        NOT NULL,
    story           TEXT,                            -- verbatim from source, HTML and all; cleaning is a derived-side concern
    published       TIMESTAMPTZ NOT NULL,
    last_modified   TIMESTAMPTZ,
    premium         BOOLEAN     NOT NULL DEFAULT FALSE,
    source_payload  JSONB       NOT NULL,            -- full item, raw
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, news_id)
);
CREATE INDEX idx_player_news_player ON player_news (player_id, published DESC);
```

Insert-only. No `updated_at` — rows are never updated. Follows `player_projection_raw` conventions: no FK, verbatim landing, `source_payload` JSONB through the Jackson 3 write path already proven byte-identical (4.M R-5).

**V16 (Commit C) — extension + derived table:**

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE news_embedding (
    id        UUID PRIMARY KEY,       -- deterministic: nameUUIDFromBytes(source:newsId:modelTag)
    content   TEXT,
    metadata  JSONB,                  -- player_id, espn_news_id, published, headline, embedding_model
    embedding VECTOR(1536)
);
CREATE INDEX idx_news_embedding_hnsw ON news_embedding
    USING hnsw (embedding vector_cosine_ops);
```

Column names `content`/`metadata`/`embedding` are the validator's expected set — bytecode-verified. `jsonb` where the store's own template says `json` is safe (validator never checks types; the store's INSERT casts `?::jsonb`). Verify first that no earlier migration already ran `CREATE EXTENSION vector` — if one did, drop the line here and note it.

## 6. Ingestion (Commit B) — `news/` package

Follows the `TeamScheduleSyncService` shape end to end.

- **`EspnNewsClient`** — injected `RestClient.Builder`, base `https://site.api.espn.com`, GET `/apis/fantasy/v2/games/ffl/news/players?limit=50&playerId={espnId}`. Returns the parsed feed; throws `EspnUnavailableException` on transport failure (existing 502 mapping).
- **`PlayerNewsSyncService`** — iterates players `WHERE espn_id IS NOT NULL AND position IN (QB, RB, WR, TE)` (board scope; K/DST excluded to match `DraftBoardService`). Per player: fetch feed, **filter `type == "Rotowire"` at the write boundary** (the seasonType==2 pattern), map to entities, hand to writer. Sequential calls; no parallelism this increment (the reconciliation concurrency item stays deferred). Three-outcome sync report: `{playersSynced, itemsInserted, itemsSkippedExisting}` plus a WARN list of players whose fetch failed — skip-never-deletes, one player's failure never aborts the run.
- **`PlayerNewsWriter`** — separate bean for the `@Transactional` proxy (the TeamScheduleWriter lesson). Dedup = insert-only existence check on `(source, news_id)`; never update an existing row.
- **`PlayerNews` entity** — composite PK via `@IdClass(PlayerNewsId)`, Lombok, `Persistable` + `@Builder.Default isNew` (the draft_pick pattern), `@PrePersist` audit.
- **`NewsController`** — `POST /api/news/sync` → sync report.

## 7. Embedding build (Commit C)

- **`VectorStoreConfig`** — explicit `@Bean PgVectorStore` via builder: `vectorTableName("news_embedding")`, `initializeSchema(false)`, `vectorTableValidationsEnabled(true)`, `dimensions(1536)`, `distanceType(COSINE_DISTANCE)`, `indexType(HNSW)`, injected `JdbcTemplate` + `EmbeddingModel`, default `TokenCountBatchingStrategy`.
- **`NewsEmbeddingService`** — the derived build, retryable and idempotent:
  1. Compute the deterministic UUID for every `player_news` row under the **current model tag** (property, not derived from provider config: `readoption.news.embedding-model-tag=text-embedding-3-small`).
  2. One query for existing ids in `news_embedding`; embed only the difference.
  3. Build Documents: id = deterministic UUID; text = `headline + "\n" + cleaned(story)` (HTML-strip here, derived side — landing stays verbatim); metadata = `{player_id, espn_news_id, published(ISO-8601 string), headline, embedding_model}`.
  4. `vectorStore.add(batch)` — the store embeds internally (batched) and upserts. **No transaction spanning the OpenAI call.** A thrown 429/exception fails the batch loudly; rerun resumes from the anti-join.
- **`NewsController`** — `POST /api/news/embed` → `{candidates, embedded, alreadyCurrent}`. Deliberately a separate endpoint from sync: ingestion must never wait on a vendor.

## 8. The tool (Commit D)

**`searchPlayerNews`** on `DraftAgentTools` — sixth `@Tool`, read-only. Parameters: `playerId` (Sleeper id, obtained from `findPlayer` or draft state — same contract as `getPlayerProfile`), `query` (free text). `topK` is a server-side property (`readoption.news.top-k=5`), **not** a `@ToolParam`. Filter: `player_id == playerId AND embedding_model == currentTag` via `FilterExpressionBuilder`. Returns items as `{published, headline, story}` — reverse-chronology after retrieval, dates always present.

Degradation: player has no espn_id → `NEWS_UNAVAILABLE_NO_ESPN_ID`; empty retrieval → `NO_NEWS_FOUND`; both as explicit strings in the tool result, never an empty list.

Tool description (the primary behavioral lever — this text IS the design; executor copies verbatim, adjusting only if review finds a defect):

> Searches ingested news reports about one NFL player (trades, signings, injuries and recovery timelines, coaching and role changes, contracts). `playerId` is the Sleeper player id from findPlayer or the draft state. `query` describes what you want to know, e.g. "trade to new team" or "injury recovery status". Returns up to 5 news items, each with a publication date. News items are point-in-time reports, NOT current facts: ALWAYS state the publication date when citing one (e.g. "per a March 2 report"), and treat older items as possibly outdated. If the result is NO_NEWS_FOUND or NEWS_UNAVAILABLE_NO_ESPN_ID, say plainly that you have no news for this player — do not speculate or fill the gap from memory.

No system-prompt change this increment — the description carries the discipline; the graduation rule applies to prompt text too. If acceptance transcripts show the description alone is insufficient, a prompt amendment is a reviewed follow-up, not a preemptive addition.

## 9. Tests and acceptance (criteria written now, before the build)

**Suite (Commit-local):**
- UUID determinism unit test: same `(source, newsId, tag)` → same UUID; tag change → different UUID.
- `PlayerNewsSyncService` Mockito tests: Rotowire filter applied; Story items dropped; failed player skipped with WARN, run continues; three-outcome report arithmetic.
- Writer dedup `@DataJpaTest` (pgvector image): re-sync inserts zero duplicates.
- Context-boot `@DataJpaTest`-adjacent test: `PgVectorStore` bean with `vectorTableValidationsEnabled=true` boots against the V16 jsonb table — **this is the empirical pin on V-1** and the standing guard against a future Spring AI adding type checks.
- Retrieval slice test: deterministic fake `EmbeddingModel` (stable 1536-dim vectors); write three documents for two players; search with player filter returns only that player's items; metadata round-trips `published`.
- Schema-parsing test updated: **six** tools exposing exactly their documented parameters (`searchPlayerNews`: playerId + query only — topK and session binding must NOT appear in the schema).

**Acceptance (behavioral regime, live stack):**
- Re-run the five starvation probes as before/after transcripts (the 4.3 template). Pass = the two plausible-but-shallow cases (team-change narrative, injury timeline) now cite retrieved news **with dates**; the loud-refusal cases remain loud where the corpus is empty.
- Grounding invariant: every cited fact traces to a tool result; every cited news fact carries its publication date (A-4).
- One deliberate staleness probe: ask about a player whose newest blurb is months old (Likely-shaped). Pass = the agent dates the report and flags its age; fail = present-tense citation of a stale item.
- Capture the **new fresh-turn token baseline** (carried item from 4.M.1: prompt grew ~+200 tokens then; the sixth tool schema grows it again). Two runs minimum — bands need error bars.

## 10. Commit boundaries

| Commit | Contents | Files |
|--------|----------|-------|
| A | Crosswalk repair + degradation constants | repair script/report; news vocabulary constants |
| B | **Ingestion live (calendar-critical)** | V15, `PlayerNews(+Id)`, repository, `EspnNewsClient`, `PlayerNewsSyncService`, `PlayerNewsWriter`, `NewsController` (sync), tests |
| C | Embedding build | pom, V16, `VectorStoreConfig`, `NewsEmbeddingService`, embed endpoint, properties, tests |
| D | Agent tool | `DraftAgentTools` (+searchPlayerNews), `AgentProperties`/news properties, schema-parsing test update, retrieval slice test |

After B lands and passes review: run the initial seed sync immediately, then schedule manual syncs ~2×/week through camp (cadence derived from A-5's ~4-item worst-case retention buffer). Automation of the schedule is deferred — a human-triggered POST is sufficient for one draft season.

## 11. Deferred from this phase (noted, not blocking)

- FantasyPros as backfill source — pending API key response; would patch the star-player March–April gap (A-5 accepted limitation).
- Story-type item chunking — graduation-gated; entity linking is pre-solved (`data-player-guid` + href ids in ESPN markup) if a transcript ever justifies it.
- `dimensions` parameter reduction (Matryoshka, 256–1536) — storage lever irrelevant at this corpus size.
- Old-generation embedding cleanup after a model swap — one manual `DELETE ... WHERE metadata->>'embedding_model' = ?`; not worth code until a swap happens.
- Sync scheduling/automation; `record_pick` tool; frontend — unchanged standing deferrals.
