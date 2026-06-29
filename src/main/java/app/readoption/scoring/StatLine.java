package app.readoption.scoring;

import java.math.BigDecimal;

/**
 * Abstraction over any source of football statistics.
 * Both historical stats (PlayerStats) and projected stats (PlayerProjection)
 * implement this interface, allowing the ScoringService to score either source
 * without knowing which one it's working with.
 *
 * The stat getters return {@link Number}, not a concrete numeric type, on purpose:
 * historical stats are genuine integers (INTEGER columns, Integer fields) while
 * projections — after the Phase 2 reconciliation — carry fractional source lines
 * or per-stat medians (NUMERIC columns, {@link BigDecimal} fields). Both Integer
 * and BigDecimal are subtypes of Number, so each entity's Lombok getter satisfies
 * this contract via a covariant return without forcing one numeric type on the
 * other. {@link ScoringService} narrows to BigDecimal internally to keep precision.
 *
 * Method names match the PlayerStats entity field names (the first implementation).
 * Null return values mean "not applicable for this position" (e.g., a RB has null passing stats).
 */
public interface StatLine {

    Number getPassingYards();

    Number getPassingTd();

    Number getInterceptions();

    Number getRushingYards();

    Number getRushingTd();

    Number getReceptions();

    Number getReceivingYards();

    Number getReceivingTd();

    Number getFumblesLost();

    Number getTwoPtConv();

    Integer getGamesPlayed();
}