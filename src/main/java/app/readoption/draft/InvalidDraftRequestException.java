package app.readoption.draft;

/**
 * Cross-field request validation the bean annotations can't express
 * (e.g. {@code userSlot <= teamCount}) — surfaces as a 400.
 */
public class InvalidDraftRequestException extends RuntimeException {

    public InvalidDraftRequestException(String message) {
        super(message);
    }
}
