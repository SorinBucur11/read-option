package app.readoption.agent;

import app.readoption.draft.DraftService;
import app.readoption.draft.DraftStateView;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The agent's three read-only tools. A plain object (never a Spring bean),
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
    private final DraftService draftService;
    private final DraftBoardService draftBoardService;
    private final ProfileScoringService profileScoringService;

    public DraftAgentTools(long sessionId,
                           ScoringRules scoringRules,
                           DraftService draftService,
                           DraftBoardService draftBoardService,
                           ProfileScoringService profileScoringService) {
        this.sessionId = sessionId;
        this.scoringRules = scoringRules;
        this.draftService = draftService;
        this.draftBoardService = draftBoardService;
        this.profileScoringService = profileScoringService;
    }

    @Tool(description = "Get the current draft state: overall pick on the clock, which team is "
            + "picking, the user's roster so far, unfilled roster slots, picks until the user's "
            + "next turn, and per-opponent positional counts for the teams picking before the "
            + "user's next turn (the survivability gap).")
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
            + "league's scoring - never compare against other formats.")
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
            + "five seasons of actual fantasy points (total, per-game, games played) plus the "
            + "current-season projection with ADP and positional rank. All points are scored under "
            + "THIS league's rules - do not compare against other formats.")
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
