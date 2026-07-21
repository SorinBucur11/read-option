package app.readoption.draft;

import java.time.Instant;

/**
 * Lifecycle of one Sleeper draft-sync loop (in-memory, per draftId — never
 * persisted; {@link DraftStatus} remains the session's persisted lifecycle).
 * WATCHING = draft still pre_draft; SYNCING = drafting and picks are mirrored.
 * COMPLETE, STOPPED and ERROR are terminal: the loop has ended and the registry
 * entry survives only for status queries.
 */
public enum DraftSyncStatus {
    WATCHING(false),
    SYNCING(false),
    COMPLETE(true),
    STOPPED(true),
    ERROR(true);

    private final boolean terminal;

    DraftSyncStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * The status report the sync endpoints return. {@code sessionId} is null
     * until the first {@code drafting} observation creates the session;
     * {@code error} is null unless the last poll failed or the loop halted.
     */
    public record Report(
            String draftId,
            DraftSyncStatus state,
            Long sessionId,
            int picksSynced,
            Instant lastPollAt,
            String error
    ) {}
}
