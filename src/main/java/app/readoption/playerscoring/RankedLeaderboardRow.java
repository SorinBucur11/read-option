package app.readoption.playerscoring;

import java.math.BigDecimal;

public interface RankedLeaderboardRow {

    String getPlayerId();

    String getFullName();

    String getPosition();

    String getTeam();

    BigDecimal getTotalPoints();

    BigDecimal getPointsPerGame();

    Integer getGamesPlayed();

    BigDecimal getAdp();

    Integer getValueRankPosition();

    Integer getValueRankOverall();

    Integer getMarketRankPosition();

    Integer getMarketRankOverall();
}