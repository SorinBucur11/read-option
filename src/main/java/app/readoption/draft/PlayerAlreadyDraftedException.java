package app.readoption.draft;

import lombok.Getter;

/**
 * The friendly 409 for can't-draft-twice. Carries the pick that took the player
 * when it came from the service pre-check; {@code overallPickNo} is null when the
 * DB backstop ({@code uq_draft_pick_player}) fired instead — the aborted
 * transaction can't be re-queried for it.
 */
@Getter
public class PlayerAlreadyDraftedException extends RuntimeException {

    private final String playerId;
    private final Integer overallPickNo;

    public PlayerAlreadyDraftedException(String playerId, Integer overallPickNo) {
        super("Player " + playerId + " was already drafted"
                + (overallPickNo != null ? " at pick " + overallPickNo : ""));
        this.playerId = playerId;
        this.overallPickNo = overallPickNo;
    }
}
