# Phase 4.4.1 — Player–item association fix (review finding R-1)

**Status:** Ratified. Single commit. Runs AFTER the NA-espn_id repair commit.
**Executor:** Claude Code. Commit ONLY the files named in §6 — explicit paths.

## 1. The defect

The 4.4 seed sync reported `itemsSkippedExisting: 180` against an **empty**
table. On an empty table that number can only mean one thing: the same
`(source, news_id)` arrived twice within the run — one ESPN item appearing in
**multiple players' feeds** (trades and signings name both players; each
player's feed carries the item under the same ESPN id).

Under V15's PK `(source, news_id)`, first-sighting-wins: the item landed under
whichever player the iteration reached first, and every other player's
association was silently dropped. Consequence: `searchPlayerNews(secondPlayer,
"trade")` filters on `player_id` and cannot see the trade blurb that names that
player. Multi-player items are systematically the highest-value category — the
exact content 4.4 exists to retrieve.

The defect is in the 4.4 spec's data model, not in the executor's code. The
observed fact is not "this item exists"; it is **"this item appeared in this
player's feed."** The key must carry the association.

## 2. Schema change — V17

```sql
-- Phase 4.4.1: the landing PK must carry the player association.
-- One ESPN item legitimately appears in several players' feeds (trades,
-- signings); (source, news_id) collapsed those associations (review R-1).
ALTER TABLE player_news DROP CONSTRAINT player_news_pkey;
ALTER TABLE player_news ADD PRIMARY KEY (source, news_id, player_id);
```

`idx_player_news_player` is unchanged. No data migration: existing rows are
valid under the widened PK; the missing associations are recovered by re-sync
(§5), not by rewriting history.

## 3. Code changes

1. **`PlayerNewsId`** gains `playerId`; `PlayerNews` `@IdClass` mapping updated
   accordingly. Equals/hashCode cover all three fields (Lombok per convention).
2. **`PlayerNewsWriter`** — the existence check keys on the triple. Same
   insert-only, first-sighting-wins semantics — but now per (item, player).
3. **`NewsEmbeddingService.embeddingId`** widens to
   `nameUUIDFromBytes(source:newsId:playerId:modelTag)` — signature gains
   `playerId`; all call sites updated. Without this, the second association's
   vector would upsert over the first and re-destroy the linkage one layer up.
   Javadoc: the id encodes the full association key + model generation.
4. **`phase-4.4-review.md` corrections** (this puts the doc back inside the
   executor commit rule — the spec now names it):
   - The "180 cross-player duplicate items correctly deduped" claim is replaced
     with the R-1 finding and a pointer to this spec.
   - The `player_news` table section reflects the V17 PK.
   - Header gains: "Executor-authored; owner review round R-1..R-7 applied."

## 4. Tests

- **UUID determinism test updated:** same `(source, newsId, playerId, tag)` →
  same UUID; differing `playerId` alone → different UUID.
- **Writer dedup `@DataJpaTest` updated + extended:** (a) the same item under
  TWO different players inserts TWO rows; (b) the same item under the same
  player twice inserts ONE row; (c) re-sync inserts zero duplicates (existing
  case, now on the triple).
- **`NewsEmbeddingService` test updated:** two landing rows sharing
  `(source, news_id)` but differing `player_id` produce two documents with two
  distinct ids.
- Full suite green; report the count.

## 5. Operational runbook (owner-executed, after diff review)

Order matters — associations dropped by the seed are recoverable **only while
the items still sit in ESPN's rolling feeds** (audit A-3/A-5). Do this the same
day the commit lands.

1. `POST /api/news/sync` — the widened PK now accepts the second-player rows.
   Expect `itemsInserted` ≥ ~180 (the seed's dropped associations that still
   survive in the feeds) and a clean `failed` list (the NA repair preceded).
2. Verify recovered associations:
   ```sql
   SELECT source, news_id, count(*) AS players
   FROM player_news GROUP BY source, news_id
   HAVING count(*) > 1 ORDER BY players DESC LIMIT 10;
   ```
   Non-empty result = associations now stored.
3. Rebuild the derived table (ids changed derivation, so the old generation is
   unreachable by the new anti-join and would DUPLICATE retrieval results if
   left in place — same model tag, same metadata, different id):
   ```sql
   TRUNCATE news_embedding;
   ```
   Then `POST /api/news/embed` — full rebuild, ~$0.07. Verify
   `embedded == candidates` and:
   ```sql
   SELECT count(*) FROM news_embedding;          -- expect == count(*) FROM player_news
   SELECT count(*), count(DISTINCT metadata->>'embedding_model')
   FROM news_embedding;                           -- expect N, 1
   ```
4. Only then: the 4.4 acceptance probes (spec §9) — the transcripts must run
   against a corpus that actually contains its trade news.

## 6. Files this commit may touch (exhaustive)

- `src/main/resources/db/migration/V17__widen_player_news_pk.sql`
- `src/main/java/app/readoption/news/PlayerNewsId.java`
- `src/main/java/app/readoption/news/PlayerNews.java`
- `src/main/java/app/readoption/news/PlayerNewsWriter.java`
- `src/main/java/app/readoption/news/NewsEmbeddingService.java`
- `src/test/java/app/readoption/news/NewsEmbeddingServiceTest.java`
- `src/test/java/app/readoption/news/PlayerNewsWriterDataJpaTest.java` (actual
  dedup-test file name per repo; extend in place)
- `docs/specs/phase-4.4.1-spec.md` (this file)
- `docs/specs/phase-4.4-review.md` (corrections per §3.4 only)

Proposed message: `Phase 4.4.1 - widen player_news PK to (source, news_id,
player_id); embedding id gains playerId; review-doc corrections (R-1)`
