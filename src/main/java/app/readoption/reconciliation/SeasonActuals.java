package app.readoption.reconciliation;

/**
 * One season's actual production for a player, as retrieved from {@code player_stats} and
 * injected into the contested-player prompt as a baseline. Integer (not BigDecimal): these
 * are the genuinely-integral historical columns, and they are only <i>presented</i> to the
 * model here, never scored — so the {@code Number} scoring contract does not apply.
 *
 * <p>A null field is absent from the formatted block (same skip rule as the source stat
 * breakdown), so a sparse line stays compact.
 */
public record SeasonActuals(
        int year,
        Integer gamesPlayed,
        Integer passingYards,
        Integer passingTd,
        Integer rushingYards,
        Integer rushingTd,
        Integer receptions,
        Integer receivingYards,
        Integer receivingTd) {
}
