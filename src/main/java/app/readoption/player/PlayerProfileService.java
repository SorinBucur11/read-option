package app.readoption.player;

import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerscoring.PlayerScoring;
import app.readoption.playerscoring.PlayerScoringRepository;
import app.readoption.scoring.ScoringFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class PlayerProfileService {

    private final PlayerRepository playerRepository;
    private final PlayerScoringRepository playerScoringRepository;
    private final PlayerProjectionRepository playerProjectionRepository;
    private final int currentSeason;

    public PlayerProfileService(PlayerRepository playerRepository,
                                PlayerScoringRepository playerScoringRepository,
                                PlayerProjectionRepository playerProjectionRepository,
                                @Value("${readoption.current-season}") int currentSeason) {
        this.playerRepository = playerRepository;
        this.playerScoringRepository = playerScoringRepository;
        this.playerProjectionRepository = playerProjectionRepository;
        this.currentSeason = currentSeason;
    }

    @Transactional(readOnly = true)
    public PlayerProfile getProfile(String playerId, ScoringFormat format) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException(playerId));

        List<PlayerScoring> rows =
                playerScoringRepository.findByPlayerIdAndScoringFormatOrderByYearAsc(playerId, format);

        List<SeasonScore> history = new ArrayList<>();
        SeasonScore currentSeasonScore = null;

        for (PlayerScoring row : rows) {
            SeasonScore score = new SeasonScore(
                    row.getYear(),
                    row.getTotalPoints(),
                    row.getPointsPerGame(),
                    row.getGamesPlayed());

            if (row.getYear() < currentSeason) {
                history.add(score);
            } else if (row.getYear() == currentSeason) {
                currentSeasonScore = score;
            }
        }

        ProjectionScore projection = buildProjection(player, format, currentSeasonScore);

        return new PlayerProfile(
                player.getId(),
                player.getFullName(),
                player.getPosition(),
                player.getTeam(),
                format,
                history,
                projection);
    }

    private ProjectionScore buildProjection(Player player, ScoringFormat format,
                                            SeasonScore currentSeasonScore) {
        if (currentSeasonScore == null) {
            return null;   // no scored projection for the current season
        }
        BigDecimal adp = playerProjectionRepository
                .findByPlayerIdAndYear(player.getId(), currentSeason)
                .map(p -> p.adp(format.adpBucket()))
                .orElse(null);

        Integer positionalRank = computePositionalRank(player.getPosition(), format, adp);

        return new ProjectionScore(
                currentSeasonScore.year(),
                currentSeasonScore.totalPoints(),
                currentSeasonScore.pointsPerGame(),
                currentSeasonScore.gamesPlayed(),
                adp,
                positionalRank);
    }

    private Integer computePositionalRank(String position, ScoringFormat format, BigDecimal adp) {
        if (adp == null || position == null) {
            return null;   // undrafted / no ADP -> no positional rank
        }
        long better = playerProjectionRepository.countBetterAdpAtPosition(
                currentSeason, position, format.adpBucket().name(), adp);
        return (int) (better + 1);
    }
}