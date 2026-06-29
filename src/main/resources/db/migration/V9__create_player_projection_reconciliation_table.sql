-- V9: per-player reconciliation audit.
-- Answers "why is this projection what it is" — feeds the future draft-assistant's
-- explanations and debugging. No FK (derived/audit, mirrors player_scoring): the
-- reconciliation step writes it, the player_id is already resolved upstream.
--
-- route records whether the model was consulted at all, kept honest:
--   CONSENSUS     = CV under threshold, deterministic median, no model call
--   SINGLE_SOURCE = only one source had a row, no dispersion, no model call
--   LLM           = CV over threshold, model classified the disagreement
--   LLM_FALLBACK  = model call failed, fell back to the median
CREATE TABLE player_projection_reconciliation (
    player_id      VARCHAR(20) NOT NULL,
    year           INTEGER     NOT NULL,
    source_count   INTEGER     NOT NULL,
    cv             NUMERIC(6,4),          -- null for single-source (no dispersion)
    route          VARCHAR(20) NOT NULL,  -- CONSENSUS | SINGLE_SOURCE | LLM | LLM_FALLBACK
    llm_verdict    VARCHAR(30),           -- enum name, only when route=LLM
    confidence     VARCHAR(10),           -- only when route=LLM
    chosen_source  VARCHAR(50) NOT NULL,  -- 'consensus' | source name
    rationale      TEXT,                  -- only when route=LLM
    model          VARCHAR(50),           -- only when route=LLM
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (player_id, year)
);
