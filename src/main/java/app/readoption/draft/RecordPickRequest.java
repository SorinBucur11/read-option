package app.readoption.draft;

import jakarta.validation.constraints.NotBlank;

/**
 * The client names only the player. The server assigns {@code overallPickNo} —
 * deliberately no sequence field here, so a stale client can never renumber the draft.
 */
public record RecordPickRequest(
        @NotBlank String playerId
) {
}
