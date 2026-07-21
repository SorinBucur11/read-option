package app.readoption.draft;

/**
 * 409 for Sleeper-sync collisions: starting a loop that is already running, or
 * recording a manual pick on a Sleeper-synced session (single-writer by
 * prevention — picks arrive via sync only).
 */
public class DraftSyncConflictException extends RuntimeException {

    public DraftSyncConflictException(String message) {
        super(message);
    }
}
