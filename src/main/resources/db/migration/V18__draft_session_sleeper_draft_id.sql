-- V18: bind a draft_session to a live Sleeper draft.
-- NULL = manual session (the only kind before Phase 5.0).
-- UNIQUE: one session per external draft, ever — relinking an already-synced
-- draft must resume its session, not create a sibling.
ALTER TABLE draft_session
    ADD COLUMN sleeper_draft_id VARCHAR(32);

ALTER TABLE draft_session
    ADD CONSTRAINT uq_draft_session_sleeper_draft_id UNIQUE (sleeper_draft_id);

-- (Postgres UNIQUE permits multiple NULLs — manual sessions are unaffected.)
