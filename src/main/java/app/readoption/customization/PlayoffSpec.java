package app.readoption.customization;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Playoff structure — captured now, consumed in Phase 4 (playoff-weeks
 * strength-of-schedule). Nullable at the {@link LeagueRulesSpec} level because most
 * users won't mention it.
 *
 * <p>Cross-field rules ({@code playoffTeams <= roster.teamCount},
 * {@code playoffEndWeek >= playoffStartWeek}) live in the object-level
 * {@code LeagueRulesValidator}, not here — Jakarta annotations can't see across fields.
 */
public record PlayoffSpec(
        @Min(2) @Max(16) int playoffTeams,
        @Min(14) @Max(18) int playoffStartWeek,
        @Min(14) @Max(18) int playoffEndWeek) {
}
