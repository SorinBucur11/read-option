package app.readoption.scoring;

/**
 * Abstraction over any source of football statistics.
 * Both historical stats (PlayerStats) and projected stats (PlayerProjection, future)
 * implement this interface, allowing the ScoringService to score either source
 * without knowing which one it's working with.
 *
 * Method names match the PlayerStats entity field names (the first implementation).
 * Null return values mean "not applicable for this position" (e.g., a RB has null passing stats).
 */
public interface StatLine {

    Integer getPassingYards();

    Integer getPassingTd();

    Integer getInterceptions();

    Integer getRushingYards();

    Integer getRushingTd();

    Integer getReceptions();

    Integer getReceivingYards();

    Integer getReceivingTd();

    Integer getFumblesLost();

    Integer getTwoPtConv();

    Integer getGamesPlayed();
}