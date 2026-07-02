package app.readoption.scoring;

import java.util.Set;

/**
 * Roster configuration for a fantasy football league.
 *
 * This doesn't affect scoring (a player's fantasy points are the same
 * regardless of league format). It affects DRAFT STRATEGY — how valuable
 * each position is depends on how many roster slots exist.
 *
 * Phase 3: populated from natural language via the customization resolver and
 * persisted on {@code league_config}; scoring rules travel separately as
 * {@code ScoringRules} beside this record in {@code LeagueRules} (the old
 * {@code scoringFormat} field graduated there). {@code superflexSlots} is a
 * roster fact — it changes which lineups are legal, not how points are scored.
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
        int superflexSlots,
        int benchSlots
) {
    /**
     * Default: 10-team, 1 QB / 2 RB / 2 WR / 1 TE / 1 FLEX (RB/WR) / no superflex /
     * 6 bench.
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
                0,
                6
        );
    }
}
