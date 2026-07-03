package app.readoption.draft;

import lombok.Getter;

@Getter
public class DraftSessionNotActiveException extends RuntimeException {

    private final DraftStatus status;

    public DraftSessionNotActiveException(long sessionId, DraftStatus status) {
        super("Draft session " + sessionId + " is " + status + ", not ACTIVE");
        this.status = status;
    }
}
