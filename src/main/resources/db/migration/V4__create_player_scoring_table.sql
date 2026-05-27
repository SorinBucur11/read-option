-- V4: Computed fantasy points per player-season-format combination.
-- Derived table — populated by ScoringService, no FK constraints.
-- PK supports multiple scoring formats per stat line.

CREATE TABLE player_scoring (
                                player_id       VARCHAR(20)     NOT NULL,
                                year            INTEGER         NOT NULL,
                                scoring_format  VARCHAR(20)     NOT NULL,
                                total_points    DECIMAL(8, 2)   NOT NULL,
                                points_per_game DECIMAL(6, 2)   NOT NULL,
                                games_played    INTEGER         NOT NULL,
                                created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
                                updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
                                PRIMARY KEY (player_id, year, scoring_format)
);

-- Leaderboard queries: "top N players in 2024, Standard scoring"
CREATE INDEX idx_scoring_year_format ON player_scoring (year, scoring_format);