package app.readoption.reconciliation;

/**
 * Outcome of a reconciliation run. Mirrors the ESPN three-outcome report idiom, split
 * across the routes the engine actually took. On a dry-run the counts are zero and
 * {@code cvDistribution} carries the calibration data; on a real run the counts are
 * populated and {@code cvDistribution} is null.
 */
public record ReconciliationReport(
        int season,
        boolean dryRun,
        int reconciledConsensus,
        int reconciledLlm,
        int reconciledSingleSource,
        int fellBack,
        int skipped,
        CvDistribution cvDistribution
) {
}
