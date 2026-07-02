package app.readoption.customization;

import app.readoption.customization.validation.ValidationIssue;

import java.util.List;

/**
 * Confirm was called on a config that still has BLOCKING issues. The confirm gate is
 * the only writer, and it refuses rather than persisting a config the user must still
 * repair — mapped to 409 + the issue list by the global handler.
 */
public class LeagueConfigNotReadyException extends RuntimeException {

    private final transient List<ValidationIssue> issues;

    public LeagueConfigNotReadyException(List<ValidationIssue> issues) {
        super("league config has blocking issues and cannot be confirmed");
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
