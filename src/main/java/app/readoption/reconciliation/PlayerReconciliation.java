package app.readoption.reconciliation;

/**
 * The REASON-phase result for one player: the staged mart line and its audit row.
 * Collected in memory (no transaction open) and handed to the writer.
 */
public record PlayerReconciliation(StagedLine line, PlayerProjectionReconciliation audit) {
}
