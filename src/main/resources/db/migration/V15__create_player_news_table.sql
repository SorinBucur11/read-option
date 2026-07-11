-- Phase 4.4 Commit B: the news LANDING table — insert-only, verbatim, permanent.
-- Retention at the source is opaque (audit A-3): rolled-off items are
-- unrecoverable, so this table is the only permanent record. Follows the
-- player_projection_raw landing conventions: no FK (news can land before the
-- player row's full lifecycle), verbatim payload, source_payload JSONB.
-- No updated_at: rows are never updated (dedup is an existence check, never a merge).
CREATE TABLE player_news (
    source          TEXT        NOT NULL,           -- 'espn'
    news_id         TEXT        NOT NULL,           -- ESPN item id, verbatim
    player_id       TEXT        NOT NULL,           -- Sleeper player_id (our canonical key; NO FK per landing-table convention)
    espn_player_id  BIGINT      NOT NULL,           -- the id the item was fetched under
    headline        TEXT        NOT NULL,
    story           TEXT,                           -- verbatim from source, HTML and all; cleaning is a derived-side concern
    published       TIMESTAMPTZ NOT NULL,
    last_modified   TIMESTAMPTZ,
    premium         BOOLEAN     NOT NULL DEFAULT FALSE,
    source_payload  JSONB       NOT NULL,           -- full item, raw
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source, news_id)
);

CREATE INDEX idx_player_news_player ON player_news (player_id, published DESC);
