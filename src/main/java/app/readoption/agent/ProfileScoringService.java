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
import app.readoption.team.TeamContextService;
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

    /** Field label when the depth chart carries no confirmed role — never a guess. */
    static final String ROLE_UNCONFIRMED = "role unconfirmed";

    /** Injury label when no {@code injury_status} is reported. */
    static final String NO_INJURY = "no injury reported";

    /** Experience label on a null {@code years_exp} — loud, never silent (D3). */
    static final String EXPERIENCE_UNKNOWN =
            "EXPERIENCE_UNKNOWN — years of experience not available for this player";

    /**
     * The 4.4.2 derivation: Java derives the label, the LLM cites it (D4 — the
     * model performs no experience arithmetic). {@code years_exp} counts accrued
     * NFL seasons and freezes for players out of the league, so year-of-entry
     * claims are made ONLY for 0 and 1 — the values probe-verified against the
     * live counter (source audit 2026-07-12); veterans get an ordinal only.
     */
    static String deriveExperience(Integer yearsExp, int currentSeason) {
        if (yearsExp == null) {
            return EXPERIENCE_UNKNOWN;
        }
        if (yearsExp == 0) {
            return "Rookie — first NFL season (" + currentSeason
                    + " class, no NFL production yet)";
        }
        if (yearsExp == 1) {
            // "entered the NFL in", never "rookie year": 4.4.2-B — the token
            // "rookie" on a sophomore profile is the categorization attractor,
            // and it may appear in exactly ONE derivation output: years_exp == 0.
            return "2nd NFL season (entered the NFL in " + (currentSeason - 1) + ")";
        }
        return ordinal(yearsExp + 1) + " NFL season";
    }

    /** 2nd/3rd/4th/…/11th–13th/…/21st shapes. */
    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    /**
     * The F1 rule, single home: {@code injury_status} is the authoritative flag —
     * a null status reads as no injury regardless of lingering attribute fields.
     * Both the profile and the team-room entries speak this label.
     */
    static String injuryLabel(Player player) {
        return player.getInjuryStatus() != null ? player.getInjuryStatus() : NO_INJURY;
    }

    private final PlayerRepository playerRepository;
    private final PlayerStatsRepository statsRepository;
    private final PlayerProjectionRepository projectionRepository;
    private final ScoringService scoringService;
    private final TeamContextService teamContextService;
    private final int currentSeason;

    public ProfileScoringService(PlayerRepository playerRepository,
                                 PlayerStatsRepository statsRepository,
                                 PlayerProjectionRepository projectionRepository,
                                 ScoringService scoringService,
                                 TeamContextService teamContextService,
                                 @Value("${readoption.current-season}") int currentSeason) {
        this.playerRepository = playerRepository;
        this.statsRepository = statsRepository;
        this.projectionRepository = projectionRepository;
        this.scoringService = scoringService;
        this.teamContextService = teamContextService;
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

        TeamContextService.TeamContext teamContext =
                teamContextService.contextFor(player.getTeam(), currentSeason);

        // injury_status is the authoritative flag; body part and notes are attributes
        // of a reported status. Sleeper's current payloads never carry them without a
        // status, but that invariant is the vendor's, not ours — a lingering note with
        // a cleared status must read as no injury, never a floating concern.
        boolean injuryReported = player.getInjuryStatus() != null;

        // The F9 rule, same shape as F1: depth_chart_position is the authoritative
        // field of the pair, and the pair degrades atomically — an order without a
        // ladder is an orphaned number that invites model synthesis ("role
        // unconfirmed and third on the depth chart"). The reverse shape (position
        // without order) is partial but not contradictory, so it passes through.
        boolean roleConfirmed = player.getDepthChartPosition() != null;

        return new PlayerProfileView(
                player.getId(),
                player.getFullName(),
                player.getPosition(),
                player.getTeam() != null ? player.getTeam() : TeamContextService.NO_TEAM,
                deriveExperience(player.getYearsExp(), currentSeason),
                roleConfirmed ? player.getDepthChartPosition() : ROLE_UNCONFIRMED,
                roleConfirmed ? player.getDepthChartOrder() : null,
                depthChartAhead(player),
                injuryLabel(player),
                injuryReported ? player.getInjuryBodyPart() : null,
                injuryReported ? player.getInjuryNotes() : null,
                teamContext.byeWeek(),
                teamContext.earlyOpponents(),
                history,
                projection);
    }

    /**
     * Names on the same RAW sub-position ladder with a strictly lower order (a
     * team fields an order-1 LWR, RWR, and SWR simultaneously — normalized-WR
     * scoping would report false competition). Empty for the starter; null
     * (omitted) when team or role data is missing — degradation over guessing.
     */
    private List<String> depthChartAhead(Player player) {
        if (player.getTeam() == null || player.getDepthChartPosition() == null
                || player.getDepthChartOrder() == null) {
            return null;
        }
        return playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        player.getTeam(), player.getDepthChartPosition(),
                        player.getDepthChartOrder())
                .stream()
                .map(Player::getFullName)
                .toList();
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
