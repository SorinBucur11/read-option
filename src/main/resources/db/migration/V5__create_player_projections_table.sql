-- V5: player_projections table
-- External season-level projections from Sleeper (provider in `source`).
-- FK to player: a projection must reference a real player.
-- Composite PK (player_id, year): one consensus projection row per player per season.

CREATE TABLE player_projections (
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

                                    adp_std          NUMERIC(5,2),
                                    adp_half_ppr     NUMERIC(5,2),
                                    adp_ppr          NUMERIC(5,2),

                                    created_at       TIMESTAMP,
                                    updated_at       TIMESTAMP,

                                    CONSTRAINT pk_player_projections PRIMARY KEY (player_id, year),
                                    CONSTRAINT fk_player_projections_player
                                        FOREIGN KEY (player_id) REFERENCES player (id)
);

-- PK is (player_id, year); leftmost column is player_id, so a query filtering
-- by year alone can't use it (leftmost-prefix rule). The sync loads existing
-- rows via findByYear every run, so year needs its own index.
CREATE INDEX idx_projections_year ON player_projections (year);