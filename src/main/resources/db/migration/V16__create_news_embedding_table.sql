-- Phase 4.4 Commit C: pgvector extension + the DERIVED embedding table.
-- Rebuildable from player_news at any time (version derived tables, not
-- source-of-truth tables — D1); the model identifier keys the derived rows via
-- the deterministic UUID id = nameUUIDFromBytes(source:newsId:modelTag), so a
-- provider swap is a re-embed + config flip, and generations can coexist.
--
-- No earlier migration creates the extension (verified V1-V15); both the local
-- compose DB and the test container run the pgvector/pgvector:pg16 image.
CREATE EXTENSION IF NOT EXISTS vector;

-- Column names content/metadata/embedding are PgVectorStore's validated set.
-- jsonb where the store's own template says json is safe: the validator checks
-- column names + vector dimensions (pg_attribute.atttypmod), never column types,
-- and the store's INSERT casts ?::jsonb (bytecode-verified, V-1; the boot test
-- pins it empirically).
CREATE TABLE news_embedding (
    id        UUID PRIMARY KEY,       -- deterministic: nameUUIDFromBytes(source:newsId:modelTag)
    content   TEXT,
    metadata  JSONB,                  -- player_id, espn_news_id, published, headline, embedding_model
    embedding VECTOR(1536)
);

CREATE INDEX idx_news_embedding_hnsw ON news_embedding
    USING hnsw (embedding vector_cosine_ops);
