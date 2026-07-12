# Phase 4.4 Review — Vector News RAG: Ingestion, Embedding Store, `searchPlayerNews`

**Landed 2026-07-11** in five commits (`db5a966` A → `a7424d6` D, plus C-fix
`fb8508c`). 340 tests green. Spec: `phase-4.4-spec.md`; crosswalk record:
`phase-4.4-crosswalk-repair.md`.
Executor-authored; owner review round R-1..R-7 applied.

## What this phase adds

The 4.2 starvation transcripts showed the agent could retrieve *facts* (roles,
depth charts, injuries, schedule) but never the *why* — the trade, the signing,
the recovery timeline. 4.4 lands that half as a classic RAG increment that
respects the house architecture: **providers predict → engine scores → LLM
reasons**. The LLM still never emits a number; it now cites dated news reports
retrieved from our own corpus.

The pipeline, end to end:

```
ESPN player-news feed ──sync──▶ player_news (landing, permanent, verbatim)
                                    │
                              ──embed──▶ news_embedding (derived, rebuildable,
                                          OpenAI text-embedding-3-small, 1536-dim)
                                    │
                       searchPlayerNews (6th agent tool)
                       similarity search, filtered to ONE player
                       + the CURRENT embedding-model generation
```

Three design invariants carried through every layer:

1. **Time-grounding (audit A-4).** Every stored and retrieved news item carries
   its publication date; the tool description orders the model to state the date
   when citing and to treat old reports as possibly stale.
2. **Loud degradation, never empty-list silence.** A player outside the ESPN
   crosswalk returns `NEWS_UNAVAILABLE_NO_ESPN_ID`; a queryable player with a
   quiet corpus returns `NO_NEWS_FOUND` (`NewsVocabulary` — one home, the
   `TeamContextService` pattern).
3. **Landing vs derived split (D1).** `player_news` is insert-only and permanent
   (the source's retention is opaque; rolled-off items are unrecoverable —
   this table is the only durable record). `news_embedding` is disposable and
   rebuildable; a provider swap is re-embed + config flip, never a data loss.

## New tables

### `player_news` (V15, PK widened by V17) — the landing table

| Column | Type | Notes |
|---|---|---|
| `source` | TEXT PK₁ | `'espn'` for now |
| `news_id` | TEXT PK₂ | ESPN item id, verbatim |
| `player_id` | TEXT PK₃ | Sleeper id, our canonical key; **no FK** (landing convention). In the PK since V17 (R-1): the observed fact is the item–player *association* |
| `espn_player_id` | BIGINT | the id the item was fetched under |
| `headline` | TEXT | |
| `story` | TEXT | verbatim, HTML and all — cleaning is derived-side |
| `published` | TIMESTAMPTZ | the citation fact |
| `last_modified` | TIMESTAMPTZ | |
| `premium` | BOOLEAN | |
| `source_payload` | JSONB | typed item, re-serialized (audit trail) |
| `created_at` | TIMESTAMPTZ | |

Insert-only: re-syncing the same `(source, news_id, player_id)` inserts nothing
and never updates — first sighting wins, per (item, player). One item
legitimately appears in several players' feeds (trades, signings); each
association is its own row. Index `idx_player_news_player (player_id,
published DESC)`.

### `news_embedding` (V16) — the derived vector table

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | **deterministic**: `nameUUIDFromBytes(source:newsId:playerId:modelTag)` (playerId since 4.4.1/R-1) — idempotent upsert, coexisting model generations |
| `content` | TEXT | `headline + "\n" + HTML-stripped story` |
| `metadata` | JSONB | `player_id, espn_news_id, published, headline, embedding_model` |
| `embedding` | VECTOR(1536) | HNSW index, cosine ops |

Column names are `PgVectorStore`'s validated set; `jsonb` where the store's own
template says `json` is safe (the 2.0.0 validator checks names + dimensions,
never types — pinned empirically by `NewsEmbeddingStoreBootTest`). V16 also runs
`CREATE EXTENSION IF NOT EXISTS vector`. The store bean boots with
`vectorTableValidationsEnabled=true`, so schema drift fails at startup, not on a
draft-day search.

## Endpoints

Prerequisites: DB up (`docker compose up -d`), `ANTHROPIC_API_KEY` and
`SPRING_AI_OPENAI_API_KEY` env vars set (the app will not boot without the
OpenAI key since Commit C — the embedding model bean is constructed eagerly).

### 1. Ingest news — `POST /api/news/sync`

```bash
curl -X POST http://localhost:8080/api/news/sync
```

Iterates every QB/RB/WR/TE with an `espn_id` (~1,970 players, sequential
fetches — expect several minutes), keeps only `type == "Rotowire"` items, and
returns the three-outcome report plus the per-player failure list:

```json
{
  "playersSynced": 1867,
  "itemsInserted": 29980,
  "itemsSkippedExisting": 180,
  "failed": ["13621 Dillon Bell (For input string: \"NA\")", "..."]
}
```

Safe to re-run any time (dedup is an existence check). **Cadence through camp:
~2×/week manually** — the source retains as few as 4–5 blurbs per star player,
and rolled-off items are gone forever.

### 2. Build embeddings — `POST /api/news/embed`

```bash
curl -X POST http://localhost:8080/api/news/embed
```

The derived build: anti-joins `player_news` against the vectors already stored
under the current model tag, embeds only the difference (OpenAI, ~$0.05 full
corpus), and reports:

```json
{ "candidates": 29980, "embedded": 29980, "alreadyCurrent": 0 }
```

Idempotent and resumable: documents are fed to the store in 500-doc chunks with
bounded backoff, so a vendor 429/outage keeps everything landed so far and a
rerun continues from the gap (the C-fix — an un-chunked full-corpus add hit
OpenAI's 1M tokens/min limit and lost the whole batch). Deliberately a separate
endpoint from sync: **ingestion never waits on a vendor.**

### 3. Use it — the agent's sixth tool (no new endpoint)

`searchPlayerNews` is exposed to the model inside the existing advice loop:

```bash
curl -X POST http://localhost:8080/api/draft/sessions/1/advise \
  -H "Content-Type: application/json" \
  -d '{"message": "Why did Mahomes fall on draft boards? Any injury news?"}'
```

The model supplies `playerId` (from `findPlayer`/draft state) and a free-text
`query`; `topK` (5) and the session are server-side — the generated schema gives
the model no knob for either. Results come back reverse-chronological as
`{published, headline, story}`, or the loud degradation strings.

## Configuration added (`application.properties`)

```properties
spring.ai.model.chat=anthropic          # both chat autoconfigs are matchIfMissing=true:
spring.ai.model.embedding=openai        # without these pins the context builds TWO ChatModels
spring.ai.openai.embedding.model=text-embedding-3-small
readoption.news.embedding-model-tag=text-embedding-3-small   # keys the derived rows; swap = re-embed + flip
readoption.news.top-k=5
```

## Verified empirically (2026-07-11, local stack)

- Crosswalk repair: 5 of 8 null-`espn_id` draftables repaired, each id verified
  against ESPN's athlete API; 3 unresolvable upstream, degrading loudly.
- Seed sync: 29,980 items / 1,867 players landed (corpus back to 2018).
  **Review finding R-1:** the seed's `itemsSkippedExisting: 180` against an
  empty table was NOT correct dedup — the V15 PK `(source, news_id)` collapsed
  items appearing in multiple players' feeds to whichever player landed first,
  silently dropping the other associations. Fixed in Phase 4.4.1
  (`phase-4-4-1-spec.md`): V17 widens the PK to the association triple; the
  dropped associations are recovered by re-sync.
- Full-context boot: exactly one Anthropic `ChatModel` + one OpenAI
  `EmbeddingModel`; `PgVectorStore` validation passed against the live V16 table.
- Retrieval slice (fake 1536-dim `EmbeddingModel`, real pgvector container):
  player filter isolates one player's corpus; `published` round-trips the jsonb
  metadata; old-generation embeddings never surface under the current tag.

## Known follow-ups (not in this phase)

- **113 players carry the literal string `NA` as `espn_id`** (DynastyProcess
  missing-marker, pre-4.4 bug) — causes the 108 recurring sync failures. Fix
  documented in `phase-4.4-crosswalk-repair.md`; needs its own commit.
- Acceptance probes (the five starvation transcripts before/after, the
  staleness probe, the fresh-turn token baseline ×2) — the behavioral regime.
- Deferred by spec §11: FantasyPros backfill, Story-item chunking, Matryoshka
  dimensions, old-generation cleanup, sync automation.
