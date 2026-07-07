package app.readoption.agent;

import app.readoption.player.ProjectionScore;
import app.readoption.player.SeasonScore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One player's history + current-season projection, scored under the session's
 * resolved rules — the {@code get_player_profile} tool result. Compact by design
 * (scored season lines, no raw stat dumps), same budget discipline as
 * {@link app.readoption.draft.DraftStateView}. Reuses the profile endpoint's
 * {@link SeasonScore}/{@link ProjectionScore} shapes; what differs is how the
 * numbers were produced (resolved {@code ScoringRules}, never a preset default).
 *
 * <p>Phase 4.3 role block — degradation is LOUD, never a dropped field or a
 * guessed value (vocabulary in {@link app.readoption.team.TeamContextService}):
 * <ul>
 *   <li>{@code team} — abbrev, or "free agent / no team".</li>
 *   <li>{@code depthChartPosition} — the source's RAW sub-position ({@code SWR}
 *       stays {@code SWR}), or "role unconfirmed".</li>
 *   <li>{@code depthChartOrder} — null when the role is unconfirmed.</li>
 *   <li>{@code depthChartAhead} — full names on the same raw sub-position ladder
 *       with a strictly lower order; empty for the starter, null (omitted) when
 *       the role is unconfirmed.</li>
 *   <li>{@code injuryStatus} — raw source vocabulary, or "no injury reported"
 *       when no status is reported; body part and notes are attributes of a
 *       reported status and are suppressed without one.</li>
 *   <li>{@code byeWeek}/{@code earlyOpponents} — via the team LEFT JOIN; degrade
 *       to "…team context unavailable" for no/unknown team (e.g. stale OAK).</li>
 * </ul>
 *
 * <p>{@code projection} is null when the player has no current-season mart row.
 *
 * <p>{@code NON_NULL} so "omitted" degradations are truly omitted from the tool
 * result (a {@code "depthChartAhead": null} would read as a fact of unclear
 * meaning; absence plus the loud sibling label reads as "unknown").
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerProfileView(
        String playerId,
        String name,
        String position,
        String team,
        String depthChartPosition,
        Integer depthChartOrder,
        List<String> depthChartAhead,
        String injuryStatus,
        String injuryBodyPart,
        String injuryNotes,
        String byeWeek,
        List<String> earlyOpponents,
        List<SeasonScore> history,
        ProjectionScore projection
) {
}
