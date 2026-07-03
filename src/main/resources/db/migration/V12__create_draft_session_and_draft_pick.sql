-- V12: draft_session + draft_pick — persisted draft state for the Phase 4 agent.
--
-- FKs ARE present, unlike landing/derived/config tables: these are transactional
-- source-of-truth rows (same category as player_stats -> player). A pick referencing
-- a vanished session or player is corruption, not a staging artifact.
-- league_config_id stays a logical ref (config-table convention, no FK).
--
-- No team_no column: snake-draft team assignment is arithmetic over
-- (overall_pick_no, team_count) — persisting it would store a derivation that can
-- drift from its source. Revisit only if traded picks enter (then it's an observed fact).
--
-- total_rounds is frozen at session creation from the config's roster shape, so a
-- later config row can't change a running draft's length mid-flight.

CREATE TABLE draft_session (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    league_config_id BIGINT       NOT NULL,   -- logical ref, no FK (config table convention)
    season           INT          NOT NULL,
    team_count       INT          NOT NULL CHECK (team_count BETWEEN 2 AND 20),
    user_slot        INT          NOT NULL,
    total_rounds     INT          NOT NULL CHECK (total_rounds BETWEEN 1 AND 30),
    status           VARCHAR(16)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    CHECK (user_slot BETWEEN 1 AND team_count)
);

-- uq_draft_pick_player is the final arbiter of can't-draft-twice; the service
-- check exists for the friendly error. Defense in depth, deliberately.
CREATE TABLE draft_pick (
    session_id      BIGINT      NOT NULL REFERENCES draft_session (id),
    overall_pick_no INT         NOT NULL CHECK (overall_pick_no >= 1),
    player_id       VARCHAR(20) NOT NULL REFERENCES player (id),
    picked_at       TIMESTAMP   NOT NULL,
    PRIMARY KEY (session_id, overall_pick_no),
    CONSTRAINT uq_draft_pick_player UNIQUE (session_id, player_id)
);
