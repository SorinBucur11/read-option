package app.readoption.customization.validation;

/**
 * Loop-control classification for a {@link ValidationIssue}:
 *
 * <ul>
 *   <li>{@code BLOCKING} — missing-with-no-safe-default or contradictory; the config
 *       cannot proceed until the user resolves it (status {@code NEEDS_INPUT}).</li>
 *   <li>{@code ASSUMPTION} — a default was applied or an unrequested change surfaced;
 *       the user may proceed after one confirmation (status stays {@code READY}).</li>
 * </ul>
 */
public enum IssueSeverity {
    BLOCKING,
    ASSUMPTION
}
