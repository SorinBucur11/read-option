-- V8: player_projections stat columns INTEGER -> NUMERIC(7,2) (mirror V7 set).
-- Consequence of Option A reconciliation: the mart receives a real fractional
-- source line or a per-stat median of source lines. An INTEGER mart would
-- re-truncate exactly the precision V7 preserves, so the mart matches the raw type.
ALTER TABLE player_projections
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
