package app.readoption.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code get_team_context} tool result: one team's schedule facts plus its
 * depth-chart room — the graduated answer to the observed team-room gap ("I don't
 * have a way to pull his handcuff's profile without knowing that player's ID").
 * Room entries therefore carry {@code playerId} so a follow-up
 * {@code get_player_profile} needs no name-resolution round trip.
 *
 * <p>An unknown team abbreviation degrades loudly in this RESULT ({@code note}
 * names the bad input, everything else omitted by {@code NON_NULL}) — never a
 * thrown 500: the model reads the note and self-corrects.
 *
 * <p>{@code injuryStatus} on room entries speaks the profile's F1-gated label
 * ({@link ProfileScoringService#injuryLabel}); {@code drafted} is computed against
 * THIS session's picks, because a handcuff answer is only useful if availability
 * is visible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TeamRoomView(
        String team,
        String positionFilter,
        String note,
        String byeWeek,
        List<String> earlyOpponents,
        List<RoomEntry> room
) {

    public static TeamRoomView unknownTeam(String team, String positionFilter) {
        return new TeamRoomView(team, positionFilter,
                "unknown team '" + team + "' - no context available", null, null, null);
    }

    /**
     * One rung of the room ladder, raw vocabulary, availability included.
     * Class-level {@code NON_NULL} on the enclosing record does not cascade to
     * nested types — annotated here so a null {@code depthChartOrder} is omitted,
     * matching the stated omission semantics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RoomEntry(
            String playerId,
            String fullName,
            String depthChartPosition,
            Integer depthChartOrder,
            String injuryStatus,
            boolean drafted
    ) {
    }
}
