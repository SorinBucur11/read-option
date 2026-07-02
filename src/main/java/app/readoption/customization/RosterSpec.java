package app.readoption.customization;

import app.readoption.scoring.Position;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.Set;

/**
 * The roster portion of the LLM's spec — pure extraction, no resolver registry
 * involved. This is the language-populated counterpart of the engine's
 * {@link app.readoption.scoring.LeagueSettings}; the resolver maps it straight through.
 *
 * <p>{@code superflexSlots} is a roster fact (it changes which lineups are legal, and
 * therefore positional scarcity), not a scoring rule.
 */
public record RosterSpec(
        @Min(2) @Max(20) int teamCount,
        @PositiveOrZero int qbSlots,
        @PositiveOrZero int rbSlots,
        @PositiveOrZero int wrSlots,
        @PositiveOrZero int teSlots,
        @PositiveOrZero int flexSlots,
        @NotNull Set<Position> flexEligible,
        @PositiveOrZero int superflexSlots,
        @PositiveOrZero int benchSlots) {
}
