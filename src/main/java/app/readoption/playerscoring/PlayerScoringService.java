package app.readoption.playerscoring;

import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import app.readoption.scoring.ScoringFormat;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlayerScoringService {

    private static final Logger log = LoggerFactory.getLogger(PlayerScoringService.class);

    private final PlayerStatsRepository playerStatsRepository;
    private final PlayerScoringRepository playerScoringRepository;
    private final ScoringService scoringService;

    public PlayerScoringService(PlayerStatsRepository playerStatsRepository,
                                PlayerScoringRepository playerScoringRepository,
                                ScoringService scoringService) {
        this.playerStatsRepository = playerStatsRepository;
        this.playerScoringRepository = playerScoringRepository;
        this.scoringService = scoringService;
    }

    @Transactional
    public int computeAndSaveForSeason(int season) {
        List<PlayerStats> statsList = playerStatsRepository.findByYear(season);
        if (statsList.isEmpty()) {
            log.warn("No stats found for season {}, skipping scoring", season);
            return 0;
        }

        log.info("Computing scores for {} players - {} formats, season {}",
                statsList.size(), ScoringFormat.values().length, season);

        Set<PlayerScoringId> existingIds = playerScoringRepository.findByYear(season)
                .stream()
                .map(PlayerScoring::getId)
                .collect(Collectors.toSet());

        List<PlayerScoring> scoringRows = new ArrayList<>();

        for (PlayerStats stats : statsList) {
            for (ScoringFormat format : ScoringFormat.values()) {
                ScoringResult result = scoringService.calculate(stats, format);

                PlayerScoring scoring = PlayerScoring.builder()
                        .playerId(stats.getPlayerId())
                        .year(season)
                        .scoringFormat(format.name())
                        .totalPoints(result.totalPoints())
                        .pointsPerGame(result.pointsPerGame())
                        .gamesPlayed(stats.getGamesPlayed() != null ? stats.getGamesPlayed() : 0)
                        .build();

                if (existingIds.contains(scoring.getId())) {
                    scoring.markExisting();
                }

                scoringRows.add(scoring);
            }
        }

        playerScoringRepository.saveAll(scoringRows);
        log.info("Saved {} scoring rows for season {}", scoringRows.size(), season);
        return scoringRows.size();
    }

    @Transactional
    public int recomputeAllSeasons() {
        List<Integer> seasons = playerStatsRepository.findDistinctYears();
        log.info("Recomputing scores for seasons: {}", seasons);

        int totalRows = 0;
        for (int season : seasons) {
            totalRows += computeAndSaveForSeason(season);
        }

        log.info("Recompute complete. Total scoring rows: {}", totalRows);
        return totalRows;
    }
}
