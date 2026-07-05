package app.readoption.agent;

import app.readoption.player.Player;
import app.readoption.player.PlayerNotFoundException;
import app.readoption.player.PlayerRepository;
import app.readoption.player.ProjectionScore;
import app.readoption.player.SeasonScore;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import app.readoption.scoring.AdpBucket;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringRules;
import app.readoption.scoring.ScoringService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Per-player history + projection scored under the session's <b>resolved</b>
 * {@link ScoringRules} — rules required, no format default. The existing
 * {@code GET /api/players/{id}/profile} rides precomputed preset rows with a
 * {@code STANDARD_6PT} fallback; an agent tool on that path would put
 * plausible-but-wrong numbers next to a board scored under the league's real
 * rules. This service is the board's uniform in-memory path applied to one
 * player: it only assembles {@link ScoringService#calculate} results, never
 * originates a number, and never reads {@code player_scoring}.
 */
@Service
public class ProfileScoringService {

    /** The advisor's lookback window: enough seasons to see a role, not a career dump. */
    private static final int HISTORY_SEASONS = 5;

    private final PlayerRepository playerRepository;
    private final PlayerStatsRepository statsRepository;
    private final PlayerProjectionRepository projectionRepository;
    private final ScoringService scoringService;
    private final int currentSeason;

    public ProfileScoringService(PlayerRepository playerRepository,
                                 PlayerStatsRepository statsRepository,
                                 PlayerProjectionRepository projectionRepository,
                                 ScoringService scoringService,
                                 @Value("${readoption.current-season}") int currentSeason) {
        this.playerRepository = playerRepository;
        this.statsRepository = statsRepository;
        this.projectionRepository = projectionRepository;
        this.scoringService = scoringService;
        this.currentSeason = currentSeason;
    }

    @Transactional(readOnly = true)
    public PlayerProfileView profile(String playerId, ScoringRules rules) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new PlayerNotFoundException(playerId));
        Position position = parsePosition(player.getPosition());

        List<SeasonScore> history = statsRepository.findByPlayerId(playerId).stream()
                .filter(stats -> stats.getYear() < currentSeason)
                .sorted(Comparator.comparingInt(PlayerStats::getYear).reversed())
                .limit(HISTORY_SEASONS)
                .sorted(Comparator.comparingInt(PlayerStats::getYear))
                .map(stats -> toSeasonScore(stats, rules, position))
                .toList();

        ProjectionScore projection = projectionRepository
                .findByPlayerIdAndYear(playerId, currentSeason)
                .map(mart -> toProjectionScore(mart, rules, player))
                .orElse(null);

        return new PlayerProfileView(
                player.getId(),
                player.getFullName(),
                player.getPosition(),
                player.getTeam(),
                history,
                projection);
    }

    private SeasonScore toSeasonScore(PlayerStats stats, ScoringRules rules, Position position) {
        ScoringResult result = scoringService.calculate(stats, rules, position);
        return new SeasonScore(stats.getYear(), result.totalPoints(), result.pointsPerGame(),
                stats.getGamesPlayed());
    }

    /**
     * ADP reads the format-matched market column via
     * {@link AdpBucket#forReceptionPoints} — the same nearest-bucket mapping the
     * board uses, so the profile and the board never quote different markets.
     */
    private ProjectionScore toProjectionScore(PlayerProjection mart, ScoringRules rules,
                                              Player player) {
        ScoringResult result = scoringService.calculate(mart, rules,
                parsePosition(player.getPosition()));
        AdpBucket bucket = AdpBucket.forReceptionPoints(rules.pointsPerReception());
        BigDecimal adp = mart.adp(bucket);
        Integer positionalRank = computePositionalRank(player.getPosition(), bucket, adp);
        return new ProjectionScore(mart.getYear(), result.totalPoints(), result.pointsPerGame(),
                mart.getGamesPlayed(), adp, positionalRank);
    }

    private Integer computePositionalRank(String position, AdpBucket bucket, BigDecimal adp) {
        if (adp == null || position == null) {
            return null;   // undrafted / no ADP -> no positional rank
        }
        long better = projectionRepository.countBetterAdpAtPosition(
                currentSeason, position, bucket.name(), adp);
        return (int) (better + 1);
    }

    /** Player.position is a String by design (flexible ingestion); null = no TE rule fires. */
    private Position parsePosition(String position) {
        if (position == null) {
            return null;
        }
        try {
            return Position.valueOf(position);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
