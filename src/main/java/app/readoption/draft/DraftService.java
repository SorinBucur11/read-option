package app.readoption.draft;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigNotFoundException;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.player.Player;
import app.readoption.player.PlayerNotFoundException;
import app.readoption.player.PlayerRepository;
import app.readoption.scoring.Position;
import app.readoption.team.TeamContextService;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Persisted draft state: session lifecycle, server-assigned pick sequencing, and the
 * compact {@link DraftStateView} read model. No LLM code here — the 4.2 agent's tools
 * wrap these methods. Everything positional is {@link SnakeOrder} arithmetic; nothing
 * derived is persisted.
 */
@Service
public class DraftService {

    private static final String UQ_DRAFT_PICK_PLAYER = "uq_draft_pick_player";

    private final DraftSessionRepository sessionRepository;
    private final DraftPickRepository pickRepository;
    private final PlayerRepository playerRepository;
    private final LeagueConfigRepository leagueConfigRepository;
    private final TeamContextService teamContextService;
    private final int currentSeason;

    public DraftService(DraftSessionRepository sessionRepository,
                        DraftPickRepository pickRepository,
                        PlayerRepository playerRepository,
                        LeagueConfigRepository leagueConfigRepository,
                        TeamContextService teamContextService,
                        @Value("${readoption.current-season}") int currentSeason) {
        this.sessionRepository = sessionRepository;
        this.pickRepository = pickRepository;
        this.playerRepository = playerRepository;
        this.leagueConfigRepository = leagueConfigRepository;
        this.teamContextService = teamContextService;
        this.currentSeason = currentSeason;
    }

    /**
     * Starts an ACTIVE session against a confirmed league config. {@code totalRounds}
     * is frozen here as the sum of the config's roster spots, so a later config row
     * can't change a running draft's length mid-flight.
     */
    @Transactional
    public DraftSession startSession(StartDraftRequest request) {
        LeagueConfig config = leagueConfigRepository.findById(request.leagueConfigId())
                .orElseThrow(() -> new LeagueConfigNotFoundException(request.leagueConfigId()));

        int teamCount = config.getTeamCount();
        if (request.userSlot() > teamCount) {
            throw new InvalidDraftRequestException(
                    "userSlot (" + request.userSlot() + ") cannot exceed the league's teamCount ("
                            + teamCount + ")");
        }

        int totalRounds = config.getQbSlots() + config.getRbSlots() + config.getWrSlots()
                + config.getTeSlots() + config.getFlexSlots() + config.getSuperflexSlots()
                + config.getBenchSlots();

        DraftSession session = DraftSession.builder()
                .leagueConfigId(config.getId())
                .season(currentSeason)
                .teamCount(teamCount)
                .userSlot(request.userSlot())
                .totalRounds(totalRounds)
                .status(DraftStatus.ACTIVE)
                .build();
        return sessionRepository.save(session);
    }

    /**
     * Records a pick with a <b>server-assigned</b> {@code overallPickNo} = max + 1 —
     * the client never sends a sequence number. The service pre-check gives the
     * friendly 409 with the taking pick; {@code uq_draft_pick_player} at flush is the
     * final arbiter and translates to the same 409 (without the pick number — the
     * aborted transaction can't be re-queried).
     */
    @Transactional
    public DraftPickView recordPick(long sessionId, RecordPickRequest request) {
        DraftSession session = loadSession(sessionId);
        if (session.getStatus() != DraftStatus.ACTIVE) {
            throw new DraftSessionNotActiveException(sessionId, session.getStatus());
        }
        if (session.getSleeperDraftId() != null) {
            // single-writer by prevention: the sync loop is the only writer on a
            // linked session, so max+1 sequencing can never race a second caller.
            throw new DraftSyncConflictException(
                    "session " + sessionId + " is Sleeper-synced; picks arrive via sync");
        }
        String playerId = request.playerId();
        if (!playerRepository.existsById(playerId)) {
            throw new PlayerNotFoundException(playerId);
        }
        pickRepository.findBySessionIdAndPlayerId(sessionId, playerId).ifPresent(taken -> {
            throw new PlayerAlreadyDraftedException(playerId, taken.getOverallPickNo());
        });

        int pickNo = pickRepository.findMaxOverallPickNo(sessionId).orElse(0) + 1;
        DraftPick pick = DraftPick.builder()
                .sessionId(sessionId)
                .overallPickNo(pickNo)
                .playerId(playerId)
                .build();
        try {
            pickRepository.saveAndFlush(pick);
        } catch (DataIntegrityViolationException e) {
            if (isDuplicatePlayerViolation(e)) {
                throw new PlayerAlreadyDraftedException(playerId, null);
            }
            throw e;
        }

        if (pickNo == session.getTeamCount() * session.getTotalRounds()) {
            session.setStatus(DraftStatus.COMPLETE);   // same transaction as the final pick
        }

        return new DraftPickView(sessionId, pickNo,
                SnakeOrder.roundOf(pickNo, session.getTeamCount()),
                SnakeOrder.teamFor(pickNo, session.getTeamCount()),
                playerId, pick.getPickedAt());
    }

    @Transactional(readOnly = true)
    public DraftStateView getState(long sessionId) {
        DraftSession session = loadSession(sessionId);
        LeagueConfig config = leagueConfigRepository.findById(session.getLeagueConfigId())
                .orElseThrow(() -> new LeagueConfigNotFoundException(session.getLeagueConfigId()));
        List<DraftPick> picks = pickRepository.findBySessionIdOrderByOverallPickNo(sessionId);

        int teamCount = session.getTeamCount();
        int userSlot = session.getUserSlot();
        boolean active = session.getStatus() == DraftStatus.ACTIVE;
        int lastPickNo = picks.isEmpty() ? 0 : picks.get(picks.size() - 1).getOverallPickNo();

        Integer currentOverallPick = active ? lastPickNo + 1 : null;
        Integer currentTeamSlot = currentOverallPick == null
                ? null
                : SnakeOrder.teamFor(currentOverallPick, teamCount);
        boolean onTheClock = currentTeamSlot != null && currentTeamSlot == userSlot;

        OptionalInt untilUserNext = active
                ? SnakeOrder.picksUntilNextTurn(userSlot, lastPickNo, teamCount, session.getTotalRounds())
                : OptionalInt.empty();

        Map<String, Player> playersById = playerRepository.findAllById(
                        picks.stream().map(DraftPick::getPlayerId).toList())
                .stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));
        Map<Integer, List<DraftPick>> picksBySlot = picks.stream()
                .collect(Collectors.groupingBy(p -> SnakeOrder.teamFor(p.getOverallPickNo(), teamCount)));

        List<DraftPick> userPicks = picksBySlot.getOrDefault(userSlot, List.of());
        // One batch lookup for the roster's byes: LEFT-JOIN posture, loud degradation
        // for no-team/unknown-team — never a dropped entry.
        Map<String, String> byeLabels = teamContextService.byeWeekLabels(userPicks.stream()
                .map(p -> playersById.get(p.getPlayerId()))
                .filter(player -> player != null && player.getTeam() != null)
                .map(Player::getTeam)
                .collect(Collectors.toSet()));

        List<DraftStateView.RosterEntry> userRoster = userPicks
                .stream()
                .map(p -> {
                    Player player = playersById.get(p.getPlayerId());
                    String team = player != null ? player.getTeam() : null;
                    return new DraftStateView.RosterEntry(
                            p.getPlayerId(),
                            player != null ? player.getFullName() : p.getPlayerId(),
                            player != null ? player.getPosition() : null,
                            SnakeOrder.roundOf(p.getOverallPickNo(), teamCount),
                            team != null ? byeLabels.get(team)
                                    : TeamContextService.BYE_UNKNOWN_NO_TEAM);
                })
                .toList();

        return new DraftStateView(
                sessionId,
                session.getStatus(),
                currentOverallPick,
                currentTeamSlot,
                onTheClock,
                untilUserNext.isPresent() ? untilUserNext.getAsInt() : null,
                userRoster,
                unfilledSlots(config, userRoster),
                gapTeams(lastPickNo, untilUserNext, teamCount, picksBySlot, playersById));
    }

    private DraftSession loadSession(long sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new DraftSessionNotFoundException(sessionId));
    }

    /**
     * Remaining roster capacity by slot type, greedy in pick order: dedicated slot
     * first, then FLEX if eligible, then SUPERFLEX (flex-eligible plus QB), then
     * BENCH. Only slot types the config actually has appear as keys.
     */
    private Map<String, Integer> unfilledSlots(LeagueConfig config,
                                               List<DraftStateView.RosterEntry> userRoster) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        putIfConfigured(remaining, Position.QB.name(), config.getQbSlots());
        putIfConfigured(remaining, Position.RB.name(), config.getRbSlots());
        putIfConfigured(remaining, Position.WR.name(), config.getWrSlots());
        putIfConfigured(remaining, Position.TE.name(), config.getTeSlots());
        putIfConfigured(remaining, "FLEX", config.getFlexSlots());
        putIfConfigured(remaining, "SUPERFLEX", config.getSuperflexSlots());
        putIfConfigured(remaining, "BENCH", config.getBenchSlots());

        Set<Position> flexEligible = config.getFlexEligible();
        for (DraftStateView.RosterEntry entry : userRoster) {
            Position position = parsePosition(entry.position());
            if (position == null) {
                continue;
            }
            if (decrement(remaining, position.name())) {
                continue;
            }
            if (flexEligible.contains(position) && decrement(remaining, "FLEX")) {
                continue;
            }
            if ((position == Position.QB || flexEligible.contains(position))
                    && decrement(remaining, "SUPERFLEX")) {
                continue;
            }
            decrement(remaining, "BENCH");
        }
        return remaining;
    }

    /**
     * For each distinct opponent slot picking strictly before the user's next turn:
     * how many picks it makes in the gap, and its positional roster counts so far.
     * Counts only — no player dumps; tool-result budget discipline starts now.
     */
    private List<DraftStateView.GapTeam> gapTeams(int lastPickNo, OptionalInt untilUserNext,
                                                  int teamCount,
                                                  Map<Integer, List<DraftPick>> picksBySlot,
                                                  Map<String, Player> playersById) {
        List<DraftStateView.GapTeam> gapTeams = new ArrayList<>();
        if (untilUserNext.isEmpty()) {
            return gapTeams;
        }
        int nextUserPick = lastPickNo + 1 + untilUserNext.getAsInt();
        Map<Integer, Integer> picksInGapBySlot = new LinkedHashMap<>();   // insertion = pick order
        for (int pickNo = lastPickNo + 1; pickNo < nextUserPick; pickNo++) {
            picksInGapBySlot.merge(SnakeOrder.teamFor(pickNo, teamCount), 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> entry : picksInGapBySlot.entrySet()) {
            Map<Position, Integer> positionalCounts = new EnumMap<>(Position.class);
            for (DraftPick pick : picksBySlot.getOrDefault(entry.getKey(), List.of())) {
                Player player = playersById.get(pick.getPlayerId());
                Position position = player == null ? null : parsePosition(player.getPosition());
                if (position != null) {
                    positionalCounts.merge(position, 1, Integer::sum);
                }
            }
            gapTeams.add(new DraftStateView.GapTeam(entry.getKey(), entry.getValue(), positionalCounts));
        }
        return gapTeams;
    }

    private void putIfConfigured(Map<String, Integer> remaining, String key, int slots) {
        if (slots > 0) {
            remaining.put(key, slots);
        }
    }

    private boolean decrement(Map<String, Integer> remaining, String key) {
        Integer value = remaining.get(key);
        if (value == null || value == 0) {
            return false;
        }
        remaining.put(key, value - 1);
        return true;
    }

    /** Player.position is a String by design (flexible ingestion); drafted players parse. */
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

    /**
     * Unwraps to Hibernate's typed constraint violation and compares the constraint
     * <i>name</i> — message-substring matching would also work but is dialect-fragile.
     */
    private boolean isDuplicatePlayerViolation(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return UQ_DRAFT_PICK_PLAYER.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}
