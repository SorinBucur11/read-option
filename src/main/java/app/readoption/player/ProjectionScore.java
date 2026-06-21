package app.readoption.player;

import java.math.BigDecimal;

public record ProjectionScore(
        Integer year,
        BigDecimal totalPoints,
        BigDecimal pointsPerGame,
        Integer gamesPlayed,
        BigDecimal adp,
        Integer positionalRank
) {
}