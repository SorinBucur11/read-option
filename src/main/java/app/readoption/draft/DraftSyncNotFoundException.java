package app.readoption.draft;

/** 404 for status/stop on a draftId no sync loop (running or terminal) is known for. */
public class DraftSyncNotFoundException extends RuntimeException {

    public DraftSyncNotFoundException(String draftId) {
        super("no Sleeper sync known for draft " + draftId);
    }
}
