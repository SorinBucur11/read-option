package app.readoption.customization;

import app.readoption.customization.validation.IssueSeverity;
import app.readoption.customization.validation.ValidationIssue;

import java.util.List;

/**
 * One parse/refine turn's outcome: the (possibly partial) parsed object, everything
 * the validators found, and the loop-control status derived from issue severity.
 * The client carries {@code parsed} back on the next refine/confirm call — the state
 * of the conversation is this typed object, not a chat session.
 *
 * @param parsed {@code null} only on a parse failure (the model's output could not
 *               be converted at all).
 */
public record ParseResult(
        ParsedLeague parsed,
        List<ValidationIssue> issues,
        Status status) {

    /** Derive status from the issues: any BLOCKING → NEEDS_INPUT, else READY. */
    public static ParseResult of(ParsedLeague parsed, List<ValidationIssue> issues) {
        boolean blocked = issues.stream()
                .anyMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
        return new ParseResult(parsed, List.copyOf(issues), blocked ? Status.NEEDS_INPUT : Status.READY);
    }
}
