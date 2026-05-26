package app.readoption.scoring;

import java.math.BigDecimal;

/**
 * Immutable result of a fantasy scoring calculation.
 * Returned by ScoringService — this is what gets stored in the database
 * and eventually fed to Claude for draft analysis.
 */
public record ScoringResult(
        BigDecimal totalPoints,
        BigDecimal pointsPerGame
) {}
