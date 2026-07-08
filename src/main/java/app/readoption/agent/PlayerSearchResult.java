package app.readoption.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One {@code find_player} candidate — the tool that resolves a player <i>name</i>
 * to a {@code playerId} so absence from the truncated board slice is never
 * misread as absence from the league (the F7 fabrication). {@code drafted} and
 * {@code takenAtPick} are computed against THIS session's picks; {@code team}
 * degrades to {@link app.readoption.team.TeamContextService#NO_TEAM} for free
 * agents so the Cooks-style prompt reaches the degradation vocabulary.
 *
 * <p>{@code NON_NULL} so {@code takenAtPick} is truly omitted for undrafted
 * candidates rather than serialized as {@code null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerSearchResult(
        String playerId,
        String fullName,
        String position,
        String team,
        boolean drafted,
        Integer takenAtPick,
        boolean hasProjection
) {
}
