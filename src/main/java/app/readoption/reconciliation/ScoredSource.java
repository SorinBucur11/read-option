package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;

import java.math.BigDecimal;

/**
 * A landing row paired with its measuring-stick points. The points are throwaway —
 * computed in memory only to measure the spread between sources (the dispersion
 * signal) and to pick the high/low source. They are never written to the mart.
 */
public record ScoredSource(PlayerProjectionRaw line, BigDecimal points) {
}
