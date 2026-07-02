package app.readoption.customization;

/**
 * Resolved playoff structure — the domain counterpart of {@link PlayoffSpec}, mapped
 * straight through by the resolver (pure extraction, no registry). Captured this
 * phase, consumed in Phase 4 (playoff strength-of-schedule).
 */
public record PlayoffFormat(
        int playoffTeams,
        int playoffStartWeek,
        int playoffEndWeek) {
}
