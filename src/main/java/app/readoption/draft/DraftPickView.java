package app.readoption.draft;

import java.time.LocalDateTime;

/**
 * A recorded pick plus its derived snake position — round and team slot are
 * {@link SnakeOrder} arithmetic, never stored.
 */
public record DraftPickView(
        long sessionId,
        int overallPickNo,
        int round,
        int teamSlot,
        String playerId,
        LocalDateTime pickedAt
) {
}
