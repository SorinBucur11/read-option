package app.readoption.valuation;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigNotFoundException;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.draft.DraftPick;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftSession;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.DraftSessionRepository;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.AdpBucket;
import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import app.readoption.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only orchestrator over the pure {@link ReplacementLevelCalculator}: loads the
 * session's confirmed config, scores the season's mart <b>in memory</b> under that
 * config's rules, and ranks the undrafted pool by VORP.
 *
 * <p>Deliberately <b>no preset short-circuit</b> and no reads from
 * {@code player_scoring}: one uniform path for preset and custom leagues — ~400
 * {@code calculate} calls per request are trivial, and precomputation only pays once
 * a read path exists that needs it (decision recorded against phase-3-overview §5.1).
 *
 * <p>Replacement levels are the <b>static pre-draft baseline</b> — deliberately not
 * recomputed over the drained pool; the board drains, season-long scarcity doesn't.
 */
@Service
public class DraftBoardService {

    private static final Logger log = LoggerFactory.getLogger(DraftBoardService.class);

    /** VORP desc, tie-break ADP asc (nulls last), then playerId — deterministic. */
    private static final Comparator<DraftBoardView.Row> BOARD_ORDER =
            Comparator.comparing(DraftBoardView.Row::vorp).reversed()
                    .thenComparing(DraftBoardView.Row::adp,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(DraftBoardView.Row::playerId);

    private final DraftSessionRepository sessionRepository;
    private final DraftPickRepository pickRepository;
    private final LeagueConfigRepository leagueConfigRepository;
    private final PlayerProjectionRepository projectionRepository;
    private final PlayerRepository playerRepository;
    private final ScoringService scoringService;

    public DraftBoardService(DraftSessionRepository sessionRepository,
                             DraftPickRepository pickRepository,
                             LeagueConfigRepository leagueConfigRepository,
                             PlayerProjectionRepository projectionRepository,
                             PlayerRepository playerRepository,
                             ScoringService scoringService) {
        this.sessionRepository = sessionRepository;
        this.pickRepository = pickRepository;
        this.leagueConfigRepository = leagueConfigRepository;
        this.projectionRepository = projectionRepository;
        this.playerRepository = playerRepository;
        this.scoringService = scoringService;
    }

    @Transactional(readOnly = true)
    public DraftBoardView getBoard(long sessionId, Position positionFilter, int limit) {
        DraftSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DraftSessionNotFoundException(sessionId));
        LeagueConfig config = leagueConfigRepository.findById(session.getLeagueConfigId())
                .orElseThrow(() -> new LeagueConfigNotFoundException(session.getLeagueConfigId()));
        ScoringRules rules = config.toScoringRules();
        LeagueSettings settings = config.toLeagueSettings();

        List<PlayerProjection> mart = projectionRepository.findByYear(session.getSeason());
        Map<String, Player> playersById = playerRepository.findAllById(
                        mart.stream().map(PlayerProjection::getPlayerId).toList())
                .stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        // Score in memory — position threaded so a TE-premium config applies correctly.
        List<PlayerValue> values = new ArrayList<>(mart.size());
        Map<String, PlayerProjection> projectionsById = new HashMap<>();
        int unplaceable = 0;
        for (PlayerProjection projection : mart) {
            Position position = boardPosition(playersById.get(projection.getPlayerId()));
            if (position == null) {
                unplaceable++;
                continue;   // no projection-worthy position = not on the board
            }
            BigDecimal points = scoringService.calculate(projection, rules, position).totalPoints();
            values.add(new PlayerValue(projection.getPlayerId(), position, points));
            projectionsById.put(projection.getPlayerId(), projection);
        }
        if (unplaceable > 0) {
            log.warn("Draft board season {}: skipped {} mart rows without a boardable position",
                    session.getSeason(), unplaceable);
        }

        // Static pre-draft baseline over the full (undrained) pool.
        Map<Position, BigDecimal> replacementLevels = ReplacementLevelCalculator
                .replacementLevels(values, session.getTeamCount(), settings);

        Set<String> draftedPlayerIds = pickRepository
                .findBySessionIdOrderByOverallPickNo(sessionId)
                .stream()
                .map(DraftPick::getPlayerId)
                .collect(Collectors.toCollection(HashSet::new));

        // The config stores the reception axis as the resolved rules' reception value,
        // so the nearest-bucket mapping reproduces ScoringFormat.adpBucket() exactly on
        // preset leagues and handles custom rules on the same path.
        AdpBucket adpBucket = AdpBucket.forReceptionPoints(rules.pointsPerReception());

        List<DraftBoardView.Row> rows = values.stream()
                .filter(v -> !draftedPlayerIds.contains(v.playerId()))
                .filter(v -> positionFilter == null || v.position() == positionFilter)
                .map(v -> toRow(v, replacementLevels, projectionsById, playersById, adpBucket))
                .sorted(BOARD_ORDER)
                .limit(limit)
                .toList();

        return new DraftBoardView(session.getSeason(), replacementLevels, rows);
    }

    private DraftBoardView.Row toRow(PlayerValue value,
                                     Map<Position, BigDecimal> replacementLevels,
                                     Map<String, PlayerProjection> projectionsById,
                                     Map<String, Player> playersById,
                                     AdpBucket adpBucket) {
        BigDecimal vorp = value.points().subtract(replacementLevels.get(value.position()));
        Player player = playersById.get(value.playerId());
        return new DraftBoardView.Row(
                value.playerId(),
                player.getFullName(),
                value.position(),
                value.points(),
                vorp,
                projectionsById.get(value.playerId()).adp(adpBucket));
    }

    /**
     * QB/RB/WR/TE only — K/DST are out of scope for v1, and a player with no
     * (parseable) position has no baseline to be valued against.
     */
    private Position boardPosition(Player player) {
        if (player == null || player.getPosition() == null) {
            return null;
        }
        try {
            Position position = Position.valueOf(player.getPosition());
            return position == Position.K || position == Position.DEF ? null : position;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
