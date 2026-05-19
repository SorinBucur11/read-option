-- V1: Core player table
-- Stores NFL player master data from Sleeper API

CREATE TABLE player (
                        id              VARCHAR(20)     PRIMARY KEY,
                        first_name      VARCHAR(100)    NOT NULL,
                        last_name       VARCHAR(100)    NOT NULL,
                        full_name       VARCHAR(200)    NOT NULL,
                        position        VARCHAR(10),
                        team            VARCHAR(5),
                        age             INTEGER,
                        years_exp       INTEGER,
                        status          VARCHAR(20),
                        active          BOOLEAN         DEFAULT true,
                        created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
                        updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_player_position ON player(position);
CREATE INDEX idx_player_team ON player(team);
CREATE INDEX idx_player_active ON player(active);