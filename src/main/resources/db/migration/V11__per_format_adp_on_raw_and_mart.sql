-- V11: per-format ADP promotion (Phase 4.1 Task 0).
--
-- The raw landing table graduates from one ADP-as-the-source-gives-it column
-- (+ adp_format tag) to the three per-format columns the draft board needs:
-- Sleeper (provider = rotowire) publishes adp_std / adp_half_ppr / adp_ppr on
-- every projection row. ESPN publishes no per-format ADP, so its raw rows carry
-- NULLs — dropping the old single column drops ESPN's lone PPR ADP deliberately.
--
-- ADP is an observed market fact: the reconciliation writer copies these values
-- verbatim from the rotowire raw row onto the mart row, regardless of route or
-- verdict. Never derived, never converted between formats.

ALTER TABLE player_projection_raw
    DROP COLUMN adp,
    DROP COLUMN adp_format,
    ADD COLUMN adp_std      NUMERIC(6,2),
    ADD COLUMN adp_half_ppr NUMERIC(6,2),
    ADD COLUMN adp_ppr      NUMERIC(6,2);

-- Widen the mart's existing (V5) columns to match, so the verbatim raw -> mart
-- copy can never overflow a narrower target.
ALTER TABLE player_projections
    ALTER COLUMN adp_std      TYPE NUMERIC(6,2),
    ALTER COLUMN adp_half_ppr TYPE NUMERIC(6,2),
    ALTER COLUMN adp_ppr      TYPE NUMERIC(6,2);
