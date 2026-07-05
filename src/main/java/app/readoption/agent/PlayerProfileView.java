package app.readoption.agent;

import app.readoption.player.ProjectionScore;
import app.readoption.player.SeasonScore;

import java.util.List;

/**
 * One player's history + current-season projection, scored under the session's
 * resolved rules — the {@code get_player_profile} tool result. Compact by design
 * (scored season lines, no raw stat dumps), same budget discipline as
 * {@link app.readoption.draft.DraftStateView}. Reuses the profile endpoint's
 * {@link SeasonScore}/{@link ProjectionScore} shapes; what differs is how the
 * numbers were produced (resolved {@code ScoringRules}, never a preset default).
 *
 * <p>{@code projection} is null when the player has no current-season mart row.
 */
public record PlayerProfileView(
        String playerId,
        String name,
        String position,
        String team,
        List<SeasonScore> history,
        ProjectionScore projection
) {
}
