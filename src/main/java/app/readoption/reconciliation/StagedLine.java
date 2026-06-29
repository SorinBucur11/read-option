package app.readoption.reconciliation;

/**
 * A resolved mart line ready to write, plus its provenance. The engine produces one
 * of these per non-skipped player in the REASON phase; the writer turns it into a
 * {@link app.readoption.playerprojection.PlayerProjection} upsert. {@code chosenSource}
 * becomes the mart {@code source} column ('consensus' or a winning source name).
 */
public record StagedLine(
        String playerId,
        int year,
        String team,
        String chosenSource,
        ProjectionStatLine stats
) {
}
