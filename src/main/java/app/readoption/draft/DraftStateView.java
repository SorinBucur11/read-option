package app.readoption.draft;

import app.readoption.scoring.Position;

import java.util.List;
import java.util.Map;

/**
 * The compact draft-state read model — deliberately the dry run for a Phase 4.2
 * tool result, so tool-result budget discipline starts here: counts and numbers,
 * no entity or stat-line dumps.
 *
 * <p>{@code currentOverallPick}/{@code currentTeamSlot} are null once the draft is
 * COMPLETE; {@code picksUntilUserNextTurn} is null when the user has no pick left.
 * {@code gapTeams} describes each <i>distinct</i> opponent slot picking strictly
 * before the user's next turn, with positional counts only.
 *
 * <p>{@code RosterEntry.byeWeek} (Phase 4.3) is the week as a string, or a loud
 * degradation label ({@link app.readoption.team.TeamContextService} vocabulary)
 * when the player has no team or the team is unknown — shared-bye risk across the
 * user's picks without a new tool.
 */
public record DraftStateView(
        long sessionId,
        DraftStatus status,
        Integer currentOverallPick,
        Integer currentTeamSlot,
        boolean onTheClock,
        Integer picksUntilUserNextTurn,
        List<RosterEntry> userRoster,
        Map<String, Integer> unfilledSlots,
        List<GapTeam> gapTeams
) {

    public record RosterEntry(
            String playerId,
            String name,
            String position,
            int round,
            String byeWeek
    ) {
    }

    public record GapTeam(
            int teamSlot,
            int picksInGap,
            Map<Position, Integer> positionalCounts
    ) {
    }
}
