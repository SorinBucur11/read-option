package app.readoption.player;

import java.math.BigDecimal;

public record SeasonScore(
        Integer year,
        BigDecimal totalPoints,
        BigDecimal pointsPerGame,
        Integer gamesPlayed
) {
}