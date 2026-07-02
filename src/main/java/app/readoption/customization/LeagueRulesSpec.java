package app.readoption.customization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The engine-bound half of {@link ParsedLeague} — everything the deterministic
 * resolver needs to produce the engine's config. Hard-validated: there is no free-text
 * escape hatch here, because a deterministic engine consumes the result and prose is
 * useless to it (contrast {@link DraftTactics#freeformNotes()}).
 *
 * <p>{@code @Valid} cascades into the nested records — without it, the nested
 * constraints never fire. {@code playoff} is nullable: most users won't mention it,
 * and it is captured now but consumed only in Phase 4.
 */
public record LeagueRulesSpec(
        @NotNull @Valid ScoringSpec scoring,
        @NotNull @Valid RosterSpec roster,
        @Valid PlayoffSpec playoff) {
}
