package app.readoption.agent;

import app.readoption.draft.DraftPick;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftService;
import app.readoption.draft.DraftStateView;
import app.readoption.news.PlayerNewsSearchService;
import app.readoption.news.PlayerNewsView;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import app.readoption.team.TeamContextService;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The agent's read-only tools. A plain object (never a Spring bean),
 * constructed per advice request: {@code sessionId} and {@code scoringRules} are
 * Java-owned <b>fields</b>, never {@code @ToolParam}s — the generated schema gives
 * the model no way to address another session or rescore under another format.
 * Mutation ({@code record_pick}) is deliberately absent in 4.2; the human records
 * picks via the existing endpoint.
 *
 * <p>Tool argument errors (unknown position, unknown player) throw — the
 * {@code ToolCallingManager} returns them to the model as error tool-responses,
 * which is the self-correction path, not a crash.
 *
 * <p>Every tool logs entry ({@code tool exec ->}) and successful exit
 * ({@code tool exec <-}) at DEBUG. A {@code ->} with no matching {@code <-} is the
 * diagnostic signature of a tool dying mid-execution (e.g. the DB going away) —
 * the failure then re-enters the model as an error tool-response, not a log line.
 */
public class DraftAgentTools {

    private static final Logger log = LoggerFactory.getLogger(DraftAgentTools.class);

    private static final int DEFAULT_BOARD_LIMIT = 20;
    private static final int MAX_BOARD_LIMIT = 50;

    private final long sessionId;
    private final ScoringRules scoringRules;
    private final int currentSeason;
    private final DraftService draftService;
    private final DraftBoardService draftBoardService;
    private final ProfileScoringService profileScoringService;
    private final PlayerRepository playerRepository;
    private final PlayerProjectionRepository projectionRepository;
    private final DraftPickRepository draftPickRepository;
    private final TeamContextService teamContextService;
    private final PlayerNewsSearchService newsSearchService;

    public DraftAgentTools(long sessionId,
                           ScoringRules scoringRules,
                           int currentSeason,
                           DraftService draftService,
                           DraftBoardService draftBoardService,
                           ProfileScoringService profileScoringService,
                           PlayerRepository playerRepository,
                           PlayerProjectionRepository projectionRepository,
                           DraftPickRepository draftPickRepository,
                           TeamContextService teamContextService,
                           PlayerNewsSearchService newsSearchService) {
        this.sessionId = sessionId;
        this.scoringRules = scoringRules;
        this.currentSeason = currentSeason;
        this.draftService = draftService;
        this.draftBoardService = draftBoardService;
        this.profileScoringService = profileScoringService;
        this.playerRepository = playerRepository;
        this.projectionRepository = projectionRepository;
        this.draftPickRepository = draftPickRepository;
        this.teamContextService = teamContextService;
        this.newsSearchService = newsSearchService;
    }

    @Tool(description = "Get the current draft state: overall pick on the clock, which team is "
            + "picking, the user's roster so far (each entry includes the player's bye week, "
            + "so shared-bye risk across picks is visible), unfilled roster slots, picks until "
            + "the user's next turn, and per-opponent positional counts for the teams picking "
            + "before the user's next turn (the survivability gap).")
    public DraftStateView getDraftState() {
        log.debug("tool exec -> getDraftState [session {}]", sessionId);
        long start = System.nanoTime();
        DraftStateView state = draftService.getState(sessionId);
        log.debug("tool exec <- getDraftState [session {}] pick {} | {} ms",
                sessionId, state.currentOverallPick(), millisSince(start));
        return state;
    }

    @Tool(description = "Get the VORP-ranked board of available (undrafted) players, scored under "
            + "this league's rules. Each row: playerId, name, position, projected points, VORP, and "
            + "this format's ADP; replacement levels per position ride along. Optionally filter by "
            + "position (QB/RB/WR/TE) and limit rows (default 20, max 50). All points are this "
            + "league's scoring - never compare against other formats. Returns only the top N "
            + "available players by VORP - a player absent from this list is NOT necessarily "
            + "drafted; use findPlayer to check a specific player by name.")
    public DraftBoardView getDraftBoard(
            @ToolParam(required = false, description = "Optional position filter: QB, RB, WR, or TE")
            String position,
            @ToolParam(required = false, description = "Max rows to return (default 20, max 50)")
            Integer limit) {
        log.debug("tool exec -> getDraftBoard [session {}] position={} limit={}",
                sessionId, position, limit);
        long start = System.nanoTime();
        DraftBoardView board = draftBoardService.getBoard(
                sessionId, parsePosition(position), normalizeLimit(limit));
        log.debug("tool exec <- getDraftBoard [session {}] {} rows | {} ms",
                sessionId, board.rows().size(), millisSince(start));
        return board;
    }

    @Tool(description = "Get one player's detailed profile by playerId (from the board): the last "
            + "five seasons of actual fantasy points (total, per-game, games played), the "
            + "current-season projection with ADP and positional rank, plus current context - "
            + "team, depth chart role and the players ahead of him on that ladder, injury "
            + "status/detail, bye week, and weeks 1-3 opponents. Context fields degrade to "
            + "explicit 'unconfirmed'/'unavailable' labels when facts are missing - treat those "
            + "as unknown, never guess. Depth positions are raw source labels: LWR/RWR are "
            + "outside receivers, SWR is the slot receiver. All points are scored under THIS "
            + "league's rules - do not compare against other formats.")
    public PlayerProfileView getPlayerProfile(
            @ToolParam(description = "The playerId from the draft board")
            String playerId) {
        log.debug("tool exec -> getPlayerProfile [session {}] playerId={}", sessionId, playerId);
        long start = System.nanoTime();
        PlayerProfileView profile = profileScoringService.profile(playerId, scoringRules);
        log.debug("tool exec <- getPlayerProfile [session {}] {} history seasons | {} ms",
                sessionId, profile.history().size(), millisSince(start));
        return profile;
    }

    @Tool(description = "Find a player by (partial) name. Use this when a player is not visible "
            + "on the draft board - the board shows only the top N by VORP, so absence from the "
            + "board does NOT mean a player is drafted or out of the league. Returns up to 5 "
            + "candidates with playerId, position, team, whether they are drafted in THIS "
            + "session (and by which pick), and whether they have a current-season projection. "
            + "An empty result means no active player matches the name.")
    public List<PlayerSearchResult> findPlayer(
            @ToolParam(description = "Full or partial player name, case-insensitive")
            String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(
                    "name must not be blank - pass a full or partial player name");
        }
        log.debug("tool exec -> findPlayer [session {}] name={}", sessionId, name);
        long start = System.nanoTime();

        List<Player> candidates = playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc(name.trim());
        List<PlayerSearchResult> results = candidates.isEmpty()
                ? List.of()
                : toSearchResults(candidates);

        log.debug("tool exec <- findPlayer [session {}] {} candidates | {} ms",
                sessionId, results.size(), millisSince(start));
        return results;
    }

    /** Drafted status and projection existence are batch lookups — never per candidate. */
    private List<PlayerSearchResult> toSearchResults(List<Player> candidates) {
        List<String> ids = candidates.stream().map(Player::getId).toList();
        Map<String, Integer> takenAtPick = draftPickRepository
                .findBySessionIdAndPlayerIdIn(sessionId, ids).stream()
                .collect(Collectors.toMap(DraftPick::getPlayerId, DraftPick::getOverallPickNo));
        Set<String> withProjection = Set.copyOf(
                projectionRepository.findPlayerIdsWithProjection(currentSeason, ids));

        return candidates.stream()
                .map(player -> new PlayerSearchResult(
                        player.getId(),
                        player.getFullName(),
                        player.getPosition(),
                        player.getTeam() != null ? player.getTeam() : TeamContextService.NO_TEAM,
                        takenAtPick.containsKey(player.getId()),
                        takenAtPick.get(player.getId()),
                        withProjection.contains(player.getId())))
                .toList();
    }

    @Tool(description = "Get one NFL team's context: bye week, weeks 1-3 opponents, and the "
            + "depth chart room at a position - every player on that team's ladder(s) with "
            + "their playerId, order, injury status, and whether they are already drafted in "
            + "this session. Use for handcuff questions, backfield/receiver-room composition, "
            + "and role competition. Positions: QB, RB, WR, TE (WR spans the LWR/RWR/SWR "
            + "ladders: LWR/RWR are outside receivers, SWR is the slot). Team is the standard "
            + "abbreviation (SF, KC, WAS...).")
    public TeamRoomView getTeamContext(
            @ToolParam(description = "Team abbreviation, e.g. SF")
            String team,
            @ToolParam(required = false, description = "Optional position filter: QB, RB, WR, or TE")
            String position) {
        if (team == null || team.isBlank()) {
            throw new IllegalArgumentException(
                    "team must not be blank - pass the standard abbreviation, e.g. SF");
        }
        log.debug("tool exec -> getTeamContext [session {}] team={} position={}",
                sessionId, team, position);
        long start = System.nanoTime();

        String teamAbbrev = team.trim().toUpperCase();
        Position parsed = parsePosition(position);
        String normalized = parsed != null ? parsed.name() : null;

        TeamRoomView view = teamContextService.teamRoom(teamAbbrev, normalized, currentSeason)
                .map(teamRoom -> toRoomView(teamAbbrev, normalized, teamRoom))
                // model-supplied bad team: degrade loudly in the RESULT, never a 500
                .orElseGet(() -> TeamRoomView.unknownTeam(teamAbbrev, filterLabel(normalized)));

        log.debug("tool exec <- getTeamContext [session {}] team={} {} room entries | {} ms",
                sessionId, teamAbbrev, view.room() != null ? view.room().size() : 0,
                millisSince(start));
        return view;
    }

    private TeamRoomView toRoomView(String team, String normalizedPosition,
                                    TeamContextService.TeamRoom teamRoom) {
        Set<String> draftedIds = teamRoom.players().isEmpty()
                ? Set.of()
                : draftPickRepository.findBySessionIdAndPlayerIdIn(sessionId,
                        teamRoom.players().stream().map(Player::getId).toList()).stream()
                        .map(DraftPick::getPlayerId)
                        .collect(Collectors.toSet());

        List<TeamRoomView.RoomEntry> room = teamRoom.players().stream()
                .map(player -> new TeamRoomView.RoomEntry(
                        player.getId(),
                        player.getFullName(),
                        player.getDepthChartPosition(),
                        player.getDepthChartOrder(),
                        ProfileScoringService.injuryLabel(player),
                        draftedIds.contains(player.getId())))
                .toList();

        return new TeamRoomView(team, filterLabel(normalizedPosition), null,
                teamRoom.byeWeek(), teamRoom.earlyOpponents(), room);
    }

    // Tool description text is the primary behavioral lever — spec §8 verbatim.
    @Tool(description = "Searches ingested news reports about one NFL player (trades, signings, "
            + "injuries and recovery timelines, coaching and role changes, contracts). playerId "
            + "is the Sleeper player id from findPlayer or the draft state. query describes what "
            + "you want to know, e.g. \"trade to new team\" or \"injury recovery status\". "
            + "Returns up to 5 news items, each with a publication date. News items are "
            + "point-in-time reports, NOT current facts: ALWAYS state the publication date when "
            + "citing one (e.g. \"per a March 2 report\"), and treat older items as possibly "
            + "outdated. If the result is NO_NEWS_FOUND or NEWS_UNAVAILABLE_NO_ESPN_ID, say "
            + "plainly that you have no news for this player - do not speculate or fill the gap "
            + "from memory.")
    public PlayerNewsView searchPlayerNews(
            @ToolParam(description = "The Sleeper playerId, from findPlayer or the draft state")
            String playerId,
            @ToolParam(description = "Free text describing what you want to know, e.g. "
                    + "\"trade to new team\" or \"injury recovery status\"")
            String query) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException(
                    "playerId must not be blank - use findPlayer to resolve a name to a playerId");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "query must not be blank - describe what you want to know");
        }
        log.debug("tool exec -> searchPlayerNews [session {}] playerId={} query={}",
                sessionId, playerId, query);
        long start = System.nanoTime();

        PlayerNewsView view = newsSearchService.searchForPlayer(playerId.trim(), query.trim());

        log.debug("tool exec <- searchPlayerNews [session {}] playerId={} {} | {} ms",
                sessionId, playerId,
                view.note() != null ? view.note() : view.items().size() + " items",
                millisSince(start));
        return view;
    }

    private static String filterLabel(String normalizedPosition) {
        return normalizedPosition != null ? normalizedPosition : "all";
    }

    private Position parsePosition(String position) {
        if (position == null || position.isBlank()) {
            return null;
        }
        try {
            return Position.valueOf(position.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown position '" + position
                    + "' - valid filters are QB, RB, WR, TE");
        }
    }

    /**
     * Null → the documented default; out-of-range → clamped, not thrown. The
     * description already states the bounds, so an oversized ask means "as many as
     * possible" — clamping honors that without burning an on-the-clock round trip.
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_BOARD_LIMIT;
        }
        return Math.max(1, Math.min(limit, MAX_BOARD_LIMIT));
    }

    private long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
