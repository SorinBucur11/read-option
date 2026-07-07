-- V13: team context landing — nfl_team reference, team_schedule, player role/injury columns.
--
-- nfl_team PK is the SLEEPER abbreviation (canonical: it must join to player.team);
-- espn_abbrev is a crosswalk column (single divergence: Sleeper WAS / ESPN WSH).
-- bye_week is DERIVED at schedule-sync time from the schedule gap — rebuilt on every
-- sync, never hand-entered, never copied onto players (players reach it via team).
--
-- team_schedule holds team-week rows, each game twice (once per team) — matches the
-- ESPN per-team delivery shape and every read pattern. Landing semantics: no FK
-- (convention for landing/derived tables); vocabulary is Sleeper abbrevs, crosswalked
-- once at the write boundary so all read-side joins speak one vocabulary.
--
-- The five player columns land Sleeper's RAW vocabulary (32 depth_chart_position
-- values incl. LWR/RWR/SWR; 9 injury_status values) — the consumer is the LLM, which
-- reads source vocabulary natively. No enums; normalization graduates only when Java
-- logic must branch on a value.

CREATE TABLE nfl_team (
    abbrev       VARCHAR(5)  PRIMARY KEY,
    espn_abbrev  VARCHAR(5)  NOT NULL UNIQUE,
    name         VARCHAR(50) NOT NULL,
    bye_week     SMALLINT,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE team_schedule (
    team      VARCHAR(5) NOT NULL,
    season    SMALLINT   NOT NULL,
    week      SMALLINT   NOT NULL,
    opponent  VARCHAR(5) NOT NULL,
    is_home   BOOLEAN    NOT NULL,
    PRIMARY KEY (team, season, week)
);

ALTER TABLE player
    ADD COLUMN depth_chart_position VARCHAR(10),
    ADD COLUMN depth_chart_order    SMALLINT,
    ADD COLUMN injury_status        VARCHAR(20),
    ADD COLUMN injury_body_part     VARCHAR(50),
    ADD COLUMN injury_notes         VARCHAR(255);
