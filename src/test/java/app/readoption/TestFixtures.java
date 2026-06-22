package app.readoption;

import app.readoption.player.Player;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerscoring.PlayerScoring;
import app.readoption.playerstats.PlayerStats;
import app.readoption.scoring.ScoringFormat;

import java.math.BigDecimal;

/**
 * Central factory for valid test entities. Bypassing the ETL layer means a test must
 * satisfy every NOT NULL constraint itself — so that knowledge lives here, once.
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Player player(String id, String fullName, String position) {
        return Player.builder()
                .id(id)
                .firstName("First")
                .lastName("Last")
                .fullName(fullName)
                .position(position)
                .team("XX")
                .active(true)
                .build();
    }

    public static PlayerScoring scoring(String playerId, int year, ScoringFormat format, String totalPoints) {
        return PlayerScoring.builder()
                .playerId(playerId)
                .year(year)
                .scoringFormat(format)
                .totalPoints(new BigDecimal(totalPoints))
                .pointsPerGame(new BigDecimal("20.00"))
                .gamesPlayed(17)
                .build();
    }

    public static PlayerStats stat(String playerId, int year) {
        return PlayerStats.builder()
                .playerId(playerId)
                .year(year)
                .games(17)
                .gamesPlayed(0)
                .build();
    }

    public static PlayerProjection projection(String playerId, int year, BigDecimal adpStd, BigDecimal adpPpr) {
        return PlayerProjection.builder()
                .playerId(playerId)
                .year(year)
                .source("rotowire")
                .gamesPlayed(17)
                .adpStd(adpStd)
                .adpPpr(adpPpr)
                .build();
    }
}