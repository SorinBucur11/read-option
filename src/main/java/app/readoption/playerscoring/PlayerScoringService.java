package app.readoption.playerscoring;

import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerstats.PlayerStatsRepository;
import app.readoption.scoring.Scorable;
import app.readoption.scoring.ScoringFormat;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlayerScoringService {

    private static final Logger log = LoggerFactory.getLogger(PlayerScoringService.class);

    private final PlayerStatsRepository playerStatsRepository;
    private final PlayerProjectionRepository playerProjectionRepository;
    private final PlayerScoringRepository playerScoringRepository;
    private final ScoringService scoringService;
    private final int currentSeason;

    public PlayerScoringService(PlayerStatsRepository playerStatsRepository,
                                PlayerProjectionRepository playerProjectionRepository,
                                PlayerScoringRepository playerScoringRepository,
                                ScoringService scoringService,
                                @Value("${readoption.current-season}") int currentSeason) {
        this.playerStatsRepository = playerStatsRepository;
        this.playerProjectionRepository = playerProjectionRepository;
        this.playerScoringRepository = playerScoringRepository;
        this.scoringService = scoringService;
        this.currentSeason = currentSeason;
    }

    @Transactional
    public int computeAndSaveForSeason(int season) {
        // Route by the configured boundary, not data presence: completed seasons
        // (< current) score from actual stats; the current/upcoming season scores
        // from projections. Sleeper returns zero-stat rows for an unplayed season,
        // so presence of stat rows can't be trusted to mean "this year was played."
        List<? extends Scorable> lines;
        String source;
        if (season < currentSeason) {
            lines = playerStatsRepository.findByYear(season);
            source = "stat lines";
        } else {
            lines = playerProjectionRepository.findByYear(season);
            source = "projections";
        }

        if (lines.isEmpty()) {
            log.warn("No {} found for season {}, skipping scoring", source, season);
            return 0;
        }

        return scoreAndSave(lines, season, source);
    }

    @Transactional
    public int recomputeAllSeasons() {
        // Union of seasons with actuals and seasons with only projections, so
        // projection-only years (the upcoming season) are recomputed too.
        Set<Integer> seasons = new LinkedHashSet<>(playerStatsRepository.findDistinctYears());
        seasons.addAll(playerProjectionRepository.findDistinctYears());
        log.info("Recomputing scores for seasons: {}", seasons);

        int totalRows = 0;
        for (int season : seasons) {
            totalRows += computeAndSaveForSeason(season);
        }

        log.info("Recompute complete. Total scoring rows: {}", totalRows);
        return totalRows;
    }

    private int scoreAndSave(List<? extends Scorable> lines, int season, String source) {
        log.info("Computing scores for {} {} - {} formats, season {}",
                lines.size(), source, ScoringFormat.values().length, season);

        Set<PlayerScoringId> existingIds = playerScoringRepository.findByYear(season)
                .stream()
                .map(PlayerScoring::getId)
                .collect(Collectors.toSet());

        List<PlayerScoring> scoringRows = new ArrayList<>();

        for (Scorable line : lines) {
            for (ScoringFormat format : ScoringFormat.values()) {
                ScoringResult result = scoringService.calculate(line, format);

                PlayerScoring scoring = PlayerScoring.builder()
                        .playerId(line.getPlayerId())
                        .year(season)
                        .scoringFormat(format.name())
                        .totalPoints(result.totalPoints())
                        .pointsPerGame(result.pointsPerGame())
                        .gamesPlayed(line.getGamesPlayed() != null ? line.getGamesPlayed() : 0)
                        .build();

                if (existingIds.contains(scoring.getId())) {
                    scoring.markExisting();
                }

                scoringRows.add(scoring);
            }
        }

        playerScoringRepository.saveAll(scoringRows);
        log.info("Saved {} scoring rows for season {} (from {})", scoringRows.size(), season, source);
        return scoringRows.size();
    }
}