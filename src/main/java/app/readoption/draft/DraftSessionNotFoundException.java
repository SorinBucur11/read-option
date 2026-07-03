package app.readoption.draft;

public class DraftSessionNotFoundException extends RuntimeException {

    public DraftSessionNotFoundException(long sessionId) {
        super("No draft session found with id: " + sessionId);
    }
}
