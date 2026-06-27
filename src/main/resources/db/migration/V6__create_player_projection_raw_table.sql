-- V6: player_projection_raw — per-source projection landing table.
-- Staging grain: one row per (player_id, season, source). Feeds the
-- player_projections consensus mart after reconciliation.
--
-- Deliberate differences from V5 (player_projections):
--   * source is IN the PK (per-provider rows, not one consensus row).
--   * NO FK to player: this is a landing table. Referential integrity is
--     enforced at the transform step (player_id resolved via crosswalk, or
--     row routed to a review queue), not by the DB on ingest. Mirrors the
--     no-FK decision on player_scoring.
--   * single adp column (+ adp_format) instead of three: ESPN provides one
--     ADP, not per-format. Store ADP as the source gives it.
--   * source_payload JSONB: the exact selected source entry, for audit/replay.

CREATE TABLE player_projection_raw (
                                       player_id        VARCHAR(20)  NOT NULL,
                                       year             INTEGER      NOT NULL,
                                       source           VARCHAR(50)  NOT NULL,
                                       team             VARCHAR(10),
                                       games_played     INTEGER,

                                       passing_yards    INTEGER,
                                       passing_td       INTEGER,
                                       interceptions    INTEGER,
                                       rushing_yards    INTEGER,
                                       rushing_td       INTEGER,
                                       receptions       INTEGER,
                                       receiving_yards  INTEGER,
                                       receiving_td     INTEGER,
                                       fumbles_lost     INTEGER,
                                       two_pt_conv      INTEGER,

                                       adp              NUMERIC(5,2),
                                       adp_format       VARCHAR(10),

                                       source_payload   JSONB,

                                       created_at       TIMESTAMP,
                                       updated_at       TIMESTAMP,

                                       CONSTRAINT pk_player_projection_raw
                                           PRIMARY KEY (player_id, year, source)
);

-- Reconciliation reads all sources for a (player, season): leftmost-prefix
-- (player_id, year) is served by the PK, so no extra index needed for that.
-- But a per-source sweep (load/refresh all rows for one provider) filters by
-- source alone, which the PK's leftmost rule can't serve. Hence:
CREATE INDEX idx_projection_raw_source ON player_projection_raw (source);

-- V6 (cont.): enrich player with espn_id for the deterministic ESPN crosswalk.
-- Populated by CrosswalkSyncService from the DynastyProcess db_playerids map.
-- Sleeper's own espn_id is null for ~44% of players, so the crosswalk — not
-- Sleeper — is the source of truth for this column.
ALTER TABLE player ADD COLUMN espn_id VARCHAR(20);

CREATE INDEX idx_player_espn_id ON player (espn_id);