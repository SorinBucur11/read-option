-- V7: player_projection_raw stat columns INTEGER -> NUMERIC(7,2).
-- Integer rounding injects noise into the cross-source points-dispersion signal
-- the routing decision depends on, so the landing table preserves the fractional
-- projection values exactly as each source provides them.
ALTER TABLE player_projection_raw
    ALTER COLUMN passing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN passing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN interceptions   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_yards   TYPE NUMERIC(7,2),
    ALTER COLUMN rushing_td      TYPE NUMERIC(7,2),
    ALTER COLUMN receptions      TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_yards TYPE NUMERIC(7,2),
    ALTER COLUMN receiving_td    TYPE NUMERIC(7,2),
    ALTER COLUMN fumbles_lost    TYPE NUMERIC(7,2),
    ALTER COLUMN two_pt_conv     TYPE NUMERIC(7,2);
-- games_played stays INTEGER: a game count is genuinely integral and carries no
-- fractional projection noise into the dispersion signal, unlike the stat columns.
