package app.readoption.customization.validation;

/**
 * One validation finding on a parsed league. The message feeds the repair prompt and
 * the user-facing response, so it must be <b>value-bearing</b> — name the offending
 * values, not just the rule (e.g. "Playoff teams (8) cannot exceed league size (6).").
 *
 * @param field dotted path relative to the parsed object (e.g. {@code scoring.basePreset}).
 */
public record ValidationIssue(String field, IssueSeverity severity, String message) {
}
