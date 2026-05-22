-- V2: Seasonal player stats
-- Stores per-season fantasy-relevant stats from Sleeper API
-- Composite PK on (player_id, year) — one stat line per player per season
-- Kicker and DST stats intentionally excluded (low draft value, add later if needed)

CREATE TABLE player_stats (
                              player_id           VARCHAR(20)     NOT NULL,
                              year                INTEGER         NOT NULL,
                              team                VARCHAR(5),
                              games               INTEGER         NOT NULL,
                              games_played        INTEGER         NOT NULL,
                              pass_attempts       INTEGER,
                              passes_completed    INTEGER,
                              passing_yards       INTEGER,
                              passing_td          INTEGER,
                              interceptions       INTEGER,
                              rushing_attempts    INTEGER,
                              rushing_yards       INTEGER,
                              rushing_td          INTEGER,
                              targets             INTEGER,
                              receptions          INTEGER,
                              receiving_yards     INTEGER,
                              receiving_td        INTEGER,
                              two_pt_conv         INTEGER,
                              created_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
                              updated_at          TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,

                              PRIMARY KEY (player_id, year),
                              CONSTRAINT fk_player_stats_player FOREIGN KEY (player_id) REFERENCES player(id)
);

CREATE INDEX idx_player_stats_year ON player_stats(year);