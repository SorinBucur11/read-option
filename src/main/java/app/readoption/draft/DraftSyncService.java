package app.readoption.draft;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.sleeper.SleeperDraft;
import app.readoption.sleeper.SleeperDraftClient;
import app.readoption.sleeper.SleeperDraftPick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One poll of one Sleeper draft — the testable core of the Phase 5.0 sync.
 * READ (HTTP) and WRITE (via {@link DraftSyncWriter}) are phased; no transaction
 * ever spans a fetch (news-embedding precedent).
 *
 * <p>Every validation failure is an {@link IllegalStateException} with a
 * self-diagnosing message: the runner treats that type as a gate halt (immediate
 * ERROR, no retry — retrying a 3RR draft five times cannot make it snake), while
 * transport/DB failures propagate as their own types and count against the
 * error budget instead.
 */
@Service
public class DraftSyncService {

    private static final Logger log = LoggerFactory.getLogger(DraftSyncService.class);

    private final SleeperDraftClient client;
    private final DraftSessionRepository sessionRepository;
    private final DraftPickRepository pickRepository;
    private final LeagueConfigRepository leagueConfigRepository;
    private final DraftSyncWriter writer;

    public DraftSyncService(SleeperDraftClient client,
                            DraftSessionRepository sessionRepository,
                            DraftPickRepository pickRepository,
                            LeagueConfigRepository leagueConfigRepository,
                            DraftSyncWriter writer) {
        this.client = client;
        this.sessionRepository = sessionRepository;
        this.pickRepository = pickRepository;
        this.leagueConfigRepository = leagueConfigRepository;
        this.writer = writer;
    }

    /**
     * One poll: fetch the draft object, match its status exhaustively, and mirror
     * any unseen picks. Linking an already-complete draft is thereby a one-shot
     * import — session creation plus the final sweep in a single poll.
     */
    public PollReport pollOnce(String draftId, long leagueConfigId, String userId) {
        SleeperDraft draft = client.fetchDraft(draftId);
        return switch (draft.status()) {
            // no session may exist yet — draft_order is null until drafting (probe-verified)
            case "pre_draft" -> new PollReport(DraftSyncStatus.WATCHING, null, 0, 0, 0);
            case "drafting" -> {
                DraftSession session = ensureSession(draft, leagueConfigId, userId);
                yield syncPicks(draft, session, DraftSyncStatus.SYNCING);
            }
            case "complete" -> {
                DraftSession session = ensureSession(draft, leagueConfigId, userId);
                PollReport report = syncPicks(draft, session, DraftSyncStatus.COMPLETE);
                // Sleeper flips status to complete before the picks array settles
                // (Run B incident: 166/168 captured, marked COMPLETE, stopped).
                // COMPLETE is only earned when the count agrees with teams x rounds —
                // both from this same draft object, no config involvement.
                int expected = draft.settings().teams() * draft.settings().rounds();
                if (report.totalPicks() > expected) {
                    throw new IllegalStateException("draft " + draftId + " holds "
                            + report.totalPicks() + " picks but " + draft.settings().teams()
                            + " teams x " + draft.settings().rounds() + " rounds = " + expected
                            + " — more picks than the draft can hold");
                }
                if (report.totalPicks() < expected) {
                    log.warn("draft {} complete at Sleeper but picks array holds {}/{} — continuing to poll",
                            draftId, report.totalPicks(), expected);
                    yield new PollReport(DraftSyncStatus.SYNCING, report.sessionId(),
                            report.picksInserted(), report.totalPicks(),
                            expected - report.totalPicks());
                }
                if (session.getStatus() != DraftStatus.COMPLETE) {
                    writer.markComplete(session);
                }
                yield report;
            }
            // a paused-draft status, if it exists, introduces itself here and gets
            // a decision, not a guess
            default -> throw new IllegalStateException(
                    "unknown Sleeper draft status '" + draft.status() + "' for draft " + draftId);
        };
    }

    /**
     * Finds the session bound to this Sleeper draft, or creates it on the first
     * {@code drafting} observation — gates in spec order, each rejection loud and
     * specific. Creation is deferred until every fact exists, so the
     * snapshot-at-creation invariant holds. An existing session (restart after
     * crash, mid-draft relink) skips the gates: the set-difference catches up.
     */
    private DraftSession ensureSession(SleeperDraft draft, long leagueConfigId, String userId) {
        return sessionRepository.findBySleeperDraftId(draft.draftId())
                .orElseGet(() -> createSession(draft, leagueConfigId, userId));
    }

    private DraftSession createSession(SleeperDraft draft, long leagueConfigId, String userId) {
        if (!"snake".equals(draft.type())) {
            throw new IllegalStateException("draft " + draft.draftId() + " is type '"
                    + draft.type() + "' — only snake drafts are supported");
        }
        Integer reversalRound = draft.settings().reversalRound();
        if (reversalRound != null && reversalRound != 0) {
            throw new IllegalStateException("3RR drafts unsupported — draft " + draft.draftId()
                    + " has reversal_round=" + reversalRound);
        }
        Map<String, Integer> draftOrder = draft.draftOrder();
        if (draftOrder == null || !draftOrder.containsKey(userId)) {
            throw new IllegalStateException("linked user " + userId
                    + " is not in this draft (draft_order for draft " + draft.draftId()
                    + " does not contain them)");
        }
        LeagueConfig config = leagueConfigRepository.findById(leagueConfigId)
                .orElseThrow(() -> new IllegalStateException(
                        "league config " + leagueConfigId + " not found"));
        if (config.getTeamCount() != draft.settings().teams()) {
            throw new IllegalStateException("league config " + leagueConfigId + " has teamCount="
                    + config.getTeamCount() + " but Sleeper draft " + draft.draftId()
                    + " has teams=" + draft.settings().teams());
        }

        // totalRounds comes from the draft object (the observed fact), NOT the
        // config's roster-derived sum (intent) — the manual startSession path is
        // deliberately untouched.
        DraftSession session = writer.createSession(DraftSession.builder()
                .leagueConfigId(leagueConfigId)
                .season(Integer.parseInt(draft.season()))
                .teamCount(draft.settings().teams())
                .userSlot(draftOrder.get(userId))
                .totalRounds(draft.settings().rounds())
                .status(DraftStatus.ACTIVE)
                .sleeperDraftId(draft.draftId())
                .build());

        int configRounds = config.getQbSlots() + config.getRbSlots() + config.getWrSlots()
                + config.getTeSlots() + config.getFlexSlots() + config.getSuperflexSlots()
                + config.getBenchSlots();
        log.info("config expresses {} roster rounds; draft has {} — K/DEF slots not modeled in config",
                configRounds, draft.settings().rounds());
        return session;
    }

    /**
     * Set-difference on {@code pick_no} against persisted picks — NOT a
     * max-watermark: same cost, idempotent by construction, restart-safe; the
     * set-difference IS the recovery mechanism. Survivors of the per-pick gates
     * batch-insert in {@code pick_no} order.
     */
    private PollReport syncPicks(SleeperDraft draft, DraftSession session, DraftSyncStatus observed) {
        List<SleeperDraftPick> wirePicks = client.fetchPicks(draft.draftId());
        Set<Integer> persisted = pickRepository.findBySessionIdOrderByOverallPickNo(session.getId())
                .stream()
                .map(DraftPick::getOverallPickNo)
                .collect(Collectors.toSet());

        List<DraftPick> rows = new ArrayList<>();
        List<SleeperDraftPick> fresh = wirePicks.stream()
                .filter(pick -> !persisted.contains(pick.pickNo()))
                .sorted(Comparator.comparingInt(SleeperDraftPick::pickNo))
                .toList();
        for (SleeperDraftPick pick : fresh) {
            if (pick.isKeeper() != null) {
                throw new IllegalStateException("pick " + pick.pickNo() + " has is_keeper="
                        + pick.isKeeper() + " — unobserved variant (null on all probe picks), halting");
            }
            int expectedRound = SnakeOrder.roundOf(pick.pickNo(), session.getTeamCount());
            int expectedSlot = SnakeOrder.teamFor(pick.pickNo(), session.getTeamCount());
            if (expectedSlot != pick.draftSlot() || expectedRound != pick.round()) {
                throw new IllegalStateException("pick " + pick.pickNo() + ": Sleeper reports slot "
                        + pick.draftSlot() + "/round " + pick.round() + " but snake arithmetic expects slot "
                        + expectedSlot + "/round " + expectedRound + " — slot mismatch — traded pick?");
            }
            rows.add(DraftPick.builder()
                    .sessionId(session.getId())
                    .overallPickNo(pick.pickNo())
                    .playerId(pick.playerId())   // verbatim; K/DEF ids resolve against landed rows
                    .build());
        }
        if (!rows.isEmpty()) {
            writer.insertPicks(rows);
            log.info("draft {}: synced {} new pick(s), {} total", draft.draftId(), rows.size(),
                    persisted.size() + rows.size());
        }
        return new PollReport(observed, session.getId(), rows.size(), persisted.size() + rows.size(), 0);
    }

    /**
     * What one poll observed: the sync status implied by the draft's lifecycle,
     * the bound session (null while WATCHING), picks inserted by this poll, the
     * cumulative persisted count after it, and the shortfall — non-zero means
     * "Sleeper says complete but the picks array hasn't settled to the expected
     * count" ({@code expected - totalPicks}; 0 on every other path).
     */
    public record PollReport(
            DraftSyncStatus status,
            Long sessionId,
            int picksInserted,
            int totalPicks,
            int shortfall
    ) {}
}
