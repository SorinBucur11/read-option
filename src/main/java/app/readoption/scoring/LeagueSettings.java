package app.readoption.scoring;

import java.util.Set;

/**
 * Roster configuration for a fantasy football league.
 *
 * This doesn't affect scoring (a player's fantasy points are the same
 * regardless of league format). It affects DRAFT STRATEGY — how valuable
 * each position is depends on how many roster slots exist.
 *
 * For now: a data class with a default matching the user's league.
 * Phase 3: becomes a persisted entity when users configure their own leagues.
 * Phase 4: injected into Claude's prompt as context for draft optimization.
 */
public record LeagueSettings(
        int teams,
        int qbSlots,
        int rbSlots,
        int wrSlots,
        int teSlots,
        int flexSlots,
        Set<Position> flexEligible,
        int benchSlots,
        ScoringFormat scoringFormat
) {
    /**
     * Default: 10-team, 1 QB / 2 RB / 2 WR / 1 TE / 1 FLEX (RB/WR) / 6 bench.
     * Standard scoring with 6pt passing TDs.
     */
    public static LeagueSettings defaultSettings() {
        return new LeagueSettings(
                10,
                1,
                2,
                2,
                1,
                1,
                Set.of(Position.RB, Position.WR),
                6,
                ScoringFormat.STANDARD_6PT
        );
    }
}
