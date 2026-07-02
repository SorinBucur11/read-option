-- V10: user league configuration — written only by the customization confirm gate
-- (POST /api/league/confirm); nothing persists before the user confirms.
--
-- Resolved scoring rules persist as TYPED columns: they are engine-consumed, and
-- NUMERIC gives validate-on-write + queryability. tactics persists as JSONB: it is
-- LLM-consumed (the Phase 4 draft agent) and never queried. Every number here came
-- from the deterministic resolver — the LLM has no path to originate one.
--
-- No FK: there is no user table yet (derived/config table, application guarantees
-- integrity). Surrogate id: one row per confirmed league config.
CREATE TABLE league_config (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- resolved scoring rules (typed)
    reception_format    VARCHAR(20)  NOT NULL,   -- ReceptionFormat enum name (STRING, never ORDINAL)
    passing_td_points   NUMERIC(4,2) NOT NULL,
    interception_points NUMERIC(4,2) NOT NULL,
    te_reception_bonus  NUMERIC(4,2) NOT NULL,   -- resolver registry value; 0 = no TE premium

    -- roster
    team_count          INTEGER NOT NULL,
    qb_slots            INTEGER NOT NULL,
    rb_slots            INTEGER NOT NULL,
    wr_slots            INTEGER NOT NULL,
    te_slots            INTEGER NOT NULL,
    flex_slots          INTEGER NOT NULL,
    flex_eligible       VARCHAR(20) NOT NULL,    -- CSV of Position enum names, sorted (e.g. RB,TE,WR)
    superflex_slots     INTEGER NOT NULL,
    bench_slots         INTEGER NOT NULL,

    -- playoffs (nullable: captured only when the user stated them; consumed in Phase 4)
    playoff_teams       INTEGER,
    playoff_start_week  INTEGER,
    playoff_end_week    INTEGER,

    -- draft tactics (LLM-consumed, never queried)
    tactics             JSONB,

    created_at          TIMESTAMP,
    updated_at          TIMESTAMP
);
