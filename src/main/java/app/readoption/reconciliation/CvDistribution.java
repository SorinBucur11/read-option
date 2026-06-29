package app.readoption.reconciliation;

import java.util.List;

/**
 * The CV distribution returned by a dry-run — the empirical input for setting the
 * threshold so the contested tail is manageable before running for real.
 *
 * @param buckets               fixed CV buckets with a count of 2+-source players in each
 * @param twoPlusSourcePlayers  total players with ≥2 sources (the denominator)
 * @param threshold             the currently configured CV threshold
 * @param contestedAtThreshold  how many would be flagged contested at that threshold
 */
public record CvDistribution(
        List<Bucket> buckets,
        long twoPlusSourcePlayers,
        double threshold,
        long contestedAtThreshold
) {

    public record Bucket(String label, long count) {
    }
}
