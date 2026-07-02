package app.readoption.customization;

import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.ScoringRules;

/**
 * The resolved league domain object — the engine-facing result of
 * {@link LeagueRulesResolver}: fully materialized {@link ScoringRules} (every
 * multiplier a {@code BigDecimal}, nothing left to infer), roster
 * {@link LeagueSettings}, and the optional {@link PlayoffFormat}.
 *
 * <p>This is the third type in the spec → resolver → domain split. The LLM never
 * emits this type; only the deterministic resolver constructs it.
 *
 * @param playoff nullable — most leagues are configured without stating playoffs;
 *                consumed in Phase 4.
 */
public record LeagueRules(
        ScoringRules scoring,
        LeagueSettings roster,
        PlayoffFormat playoff) {
}
