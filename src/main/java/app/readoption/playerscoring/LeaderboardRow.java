package app.readoption.playerscoring;

import java.math.BigDecimal;

public record LeaderboardRow(
        String playerId,
        String fullName,
        String position,
        String team,
        BigDecimal totalPoints,
        BigDecimal pointsPerGame,
        Integer gamesPlayed
) {
}