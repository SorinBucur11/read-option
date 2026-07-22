package app.readoption.draft;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.sleeper.SleeperDraft;
import app.readoption.sleeper.SleeperDraftClient;
import app.readoption.sleeper.SleeperDraftPick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code pollOnce} — the ordered gates, the set-difference, and the snake
 * cross-check over the full p5 probe array (150/150 — the regression anchor,
 * Barkley-style). Client and repositories are mocked; the writer is verified,
 * never real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftSyncService — pollOnce gates, set-difference, snake cross-check")
class DraftSyncServiceTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String DRAFT_ID = "1382999990000000001";
    private static final String USER_ID = "87732859926102016";
    private static final long CONFIG_ID = 42L;
    private static final long SESSION_ID = 99L;

    @Mock private SleeperDraftClient client;
    @Mock private DraftSessionRepository sessionRepository;
    @Mock private DraftPickRepository pickRepository;
    @Mock private LeagueConfigRepository leagueConfigRepository;
    @Mock private DraftSyncWriter writer;

    private DraftSyncService service() {
        return new DraftSyncService(client, sessionRepository, pickRepository,
                leagueConfigRepository, writer);
    }

    // ----- fixtures -----

    private static <T> T fixture(String resource, Class<T> type) {
        try (InputStream in = DraftSyncServiceTest.class.getResourceAsStream("/sleeper/" + resource)) {
            return MAPPER.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<SleeperDraftPick> pickFixture(String resource) {
        try (InputStream in = DraftSyncServiceTest.class.getResourceAsStream("/sleeper/" + resource)) {
            return MAPPER.readValue(in, new TypeReference<List<SleeperDraftPick>>() {});
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SleeperDraft draftingDraft() {
        return fixture("draft-drafting.json", SleeperDraft.class);
    }

    private static SleeperDraft draft(String status, String type, Integer reversalRound,
                                      Map<String, Integer> draftOrder, int teams, int rounds) {
        return new SleeperDraft(DRAFT_ID, status, type, "2026",
                new SleeperDraft.Settings(teams, rounds, 120, reversalRound), draftOrder);
    }

    private static LeagueConfig config(int teamCount) {
        return LeagueConfig.builder()
                .id(CONFIG_ID)
                .teamCount(teamCount)
                .qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(2).benchSlots(5)
                .build();
    }

    private static DraftSession syncedSession(String sleeperDraftId, DraftStatus status) {
        return DraftSession.builder()
                .id(SESSION_ID).leagueConfigId(CONFIG_ID).season(2026)
                .teamCount(10).userSlot(7).totalRounds(15)
                .status(status).sleeperDraftId(sleeperDraftId)
                .build();
    }

    private static DraftSession session12x14(DraftStatus status) {
        return DraftSession.builder()
                .id(SESSION_ID).leagueConfigId(CONFIG_ID).season(2026)
                .teamCount(12).userSlot(7).totalRounds(14)
                .status(status).sleeperDraftId(DRAFT_ID)
                .build();
    }

    /**
     * Snake-consistent picks 1..count — slot/round from {@link SnakeOrder} so the
     * cross-check (tested on its own against the p5 fixture) stays out of the way
     * and the completion count gate is the only thing under test.
     */
    private static List<SleeperDraftPick> snakePicks(int count, int teams) {
        List<SleeperDraftPick> picks = new ArrayList<>();
        for (int pickNo = 1; pickNo <= count; pickNo++) {
            picks.add(new SleeperDraftPick(pickNo, SnakeOrder.roundOf(pickNo, teams),
                    SnakeOrder.teamFor(pickNo, teams), "p" + pickNo, null, pickNo % teams + 1));
        }
        return picks;
    }

    /** The writer returns the persisted session: same snapshot, id assigned. */
    private void writerAssignsId() {
        when(writer.createSession(any(DraftSession.class))).thenAnswer(inv -> {
            DraftSession s = inv.getArgument(0);
            return DraftSession.builder()
                    .id(SESSION_ID).leagueConfigId(s.getLeagueConfigId()).season(s.getSeason())
                    .teamCount(s.getTeamCount()).userSlot(s.getUserSlot())
                    .totalRounds(s.getTotalRounds()).status(s.getStatus())
                    .sleeperDraftId(s.getSleeperDraftId())
                    .build();
        });
    }

    // ----- status dispatch -----

    @Test
    @DisplayName("pre_draft -> WATCHING, no session created, no writes of any kind")
    void preDraftCreatesNothing() {
        // fixture-loaded drafts key their stubs on the id INSIDE the fixture: the id
        // in the file and a constant naming it are the same fact stored twice, and a
        // fact stored twice will eventually disagree. DRAFT_ID stays for inline drafts.
        SleeperDraft preDraft = fixture("draft-pre_draft.json", SleeperDraft.class);
        String draftId = preDraft.draftId();
        when(client.fetchDraft(draftId)).thenReturn(preDraft);

        DraftSyncService.PollReport report = service().pollOnce(draftId, CONFIG_ID, USER_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.WATCHING);
        assertThat(report.sessionId()).isNull();
        verify(writer, never()).createSession(any());
        verify(writer, never()).insertPicks(anyList());
    }

    @Test
    @DisplayName("unknown status 'paused' -> halt naming the value")
    void unknownStatusHalts() {
        when(client.fetchDraft(DRAFT_ID))
                .thenReturn(draft("paused", "snake", null, Map.of(USER_ID, 7), 10, 15));

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID))
                .withMessageContaining("paused");
    }

    // ----- session creation (first drafting observation) -----

    @Test
    @DisplayName("first drafting poll -> session snapshot from the draft object (teams/rounds/slot)")
    void firstDraftingPollCreatesSession() {
        SleeperDraft drafting = draftingDraft();
        String draftId = drafting.draftId();
        when(client.fetchDraft(draftId)).thenReturn(drafting);
        when(sessionRepository.findBySleeperDraftId(draftId)).thenReturn(Optional.empty());
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config(10)));
        writerAssignsId();
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(draftId)).thenReturn(List.of());

        DraftSyncService.PollReport report = service().pollOnce(draftId, CONFIG_ID, USER_ID);

        ArgumentCaptor<DraftSession> captor = ArgumentCaptor.forClass(DraftSession.class);
        verify(writer).createSession(captor.capture());
        DraftSession created = captor.getValue();
        assertThat(created.getSeason()).isEqualTo(2026);
        assertThat(created.getTeamCount()).isEqualTo(10);
        assertThat(created.getUserSlot()).isEqualTo(7);
        assertThat(created.getTotalRounds()).isEqualTo(15);   // the draft object, not the config sum
        assertThat(created.getStatus()).isEqualTo(DraftStatus.ACTIVE);
        assertThat(created.getSleeperDraftId()).isEqualTo(draftId);
        assertThat(created.getLeagueConfigId()).isEqualTo(CONFIG_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.SYNCING);
        assertThat(report.sessionId()).isEqualTo(SESSION_ID);
    }

    @Test
    @DisplayName("existing session (relink/restart) -> gates skipped, set-difference catches up")
    void existingSessionSkipsGates() {
        // every creation gate would fail this draft (auction, 3RR, no draft_order) —
        // but the session already exists, so none of them runs
        when(client.fetchDraft(DRAFT_ID))
                .thenReturn(draft("drafting", "auction", 3, null, 10, 15));
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID))
                .thenReturn(Optional.of(syncedSession(DRAFT_ID, DraftStatus.ACTIVE)));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(pickFixture("picks-live-3.json"));

        DraftSyncService.PollReport report = service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID);

        verify(writer, never()).createSession(any());
        assertThat(report.picksInserted()).isEqualTo(3);
    }

    @Test
    @DisplayName("gate: non-snake type -> halt naming the type")
    void gateNonSnake() {
        when(client.fetchDraft(DRAFT_ID))
                .thenReturn(draft("drafting", "auction", null, Map.of(USER_ID, 7), 10, 15));
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.empty());

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID))
                .withMessageContaining("auction");
        verify(writer, never()).createSession(any());
    }

    @Test
    @DisplayName("gate: reversal_round=3 -> halt logging the value; 0 or absent proceeds")
    void gateReversalRound() {
        when(client.fetchDraft(DRAFT_ID))
                .thenReturn(draft("drafting", "snake", 3, Map.of(USER_ID, 7), 10, 15));
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.empty());

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID))
                .withMessageContaining("3RR")
                .withMessageContaining("reversal_round=3");
        verify(writer, never()).createSession(any());
    }

    @Test
    @DisplayName("gate: linked user absent from draft_order -> halt")
    void gateUserAbsent() {
        when(client.fetchDraft(DRAFT_ID))
                .thenReturn(draft("drafting", "snake", 0, Map.of("someone-else", 1), 10, 15));
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.empty());

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID))
                .withMessageContaining("not in this draft");
        verify(writer, never()).createSession(any());
    }

    @Test
    @DisplayName("gate: config teamCount != draft teams -> halt with both values")
    void gateTeamCountMismatch() {
        SleeperDraft drafting = draftingDraft();   // teams=10
        String draftId = drafting.draftId();
        when(client.fetchDraft(draftId)).thenReturn(drafting);
        when(sessionRepository.findBySleeperDraftId(draftId)).thenReturn(Optional.empty());
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config(12)));

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(draftId, CONFIG_ID, USER_ID))
                .withMessageContaining("teamCount=12")
                .withMessageContaining("teams=10");
        verify(writer, never()).createSession(any());
    }

    // ----- pick sync -----

    @Test
    @DisplayName("set-difference inserts only unseen pick_nos")
    void setDifferenceInsertsOnlyUnseen() {
        SleeperDraft drafting = draftingDraft();
        String draftId = drafting.draftId();
        when(client.fetchDraft(draftId)).thenReturn(drafting);
        when(sessionRepository.findBySleeperDraftId(draftId))
                .thenReturn(Optional.of(syncedSession(draftId, DraftStatus.ACTIVE)));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(1).playerId("9221").build(),
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(2).playerId("7564").build()));
        when(client.fetchPicks(draftId)).thenReturn(pickFixture("picks-live-3.json"));

        DraftSyncService.PollReport report = service().pollOnce(draftId, CONFIG_ID, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DraftPick>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertPicks(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getOverallPickNo()).isEqualTo(3);
        assertThat(captor.getValue().get(0).getPlayerId()).isEqualTo("9488");
        assertThat(report.picksInserted()).isEqualTo(1);
        assertThat(report.totalPicks()).isEqualTo(3);
    }

    @Test
    @DisplayName("is_keeper non-null -> halt (unobserved variant)")
    void isKeeperHalts() {
        SleeperDraft drafting = draftingDraft();
        String draftId = drafting.draftId();
        when(client.fetchDraft(draftId)).thenReturn(drafting);
        when(sessionRepository.findBySleeperDraftId(draftId))
                .thenReturn(Optional.of(syncedSession(draftId, DraftStatus.ACTIVE)));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(draftId)).thenReturn(List.of(
                new SleeperDraftPick(1, 1, 1, "9221", true, 2)));

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(draftId, CONFIG_ID, USER_ID))
                .withMessageContaining("is_keeper=true");
        verify(writer, never()).insertPicks(anyList());
    }

    // ----- complete: the count gate (5.0-b — the Run B premature-termination incident) -----

    @Test
    @DisplayName("complete with a short picks array (the incident shape) -> SYNCING + shortfall, sweep kept, no markComplete")
    void completeShortArrayKeepsPollingTheIncidentShape() {
        // Formerly completeFinalSweep: 3 picks against a 10x15 draft used to earn
        // COMPLETE — exactly how Run B stopped at 166/168. Now the count gate
        // demands 150 and this is the shortfall path.
        SleeperDraft complete = draft("complete", "snake", 0, Map.of(USER_ID, 7), 10, 15);
        DraftSession session = syncedSession(DRAFT_ID, DraftStatus.ACTIVE);
        when(client.fetchDraft(DRAFT_ID)).thenReturn(complete);
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.of(session));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(pickFixture("picks-live-3.json"));

        DraftSyncService.PollReport report = service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.SYNCING);
        assertThat(report.picksInserted()).isEqualTo(3);
        assertThat(report.shortfall()).isEqualTo(147);
        verify(writer, never()).markComplete(any());
    }

    @Test
    @DisplayName("complete at full count on an already-COMPLETE session -> no re-mark")
    void completeAlreadyComplete() {
        // 5.0-b: fixture grew from 0 to the full 150 — the empty array no longer
        // reaches the mark/re-mark branch (it is a shortfall now); assertion unchanged.
        SleeperDraft complete = draft("complete", "snake", 0, Map.of(USER_ID, 7), 10, 15);
        DraftSession session = syncedSession(DRAFT_ID, DraftStatus.COMPLETE);
        when(client.fetchDraft(DRAFT_ID)).thenReturn(complete);
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.of(session));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(snakePicks(150, 10));

        DraftSyncService.PollReport report = service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.COMPLETE);
        verify(writer, never()).markComplete(any());
    }

    @Test
    @DisplayName("complete + picks at 168/168 -> COMPLETE, markComplete, shortfall 0")
    void completeFullCountEarnsComplete() {
        SleeperDraft complete = draft("complete", "snake", 0, Map.of(USER_ID, 7), 12, 14);
        DraftSession session = session12x14(DraftStatus.ACTIVE);
        when(client.fetchDraft(DRAFT_ID)).thenReturn(complete);
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.of(session));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(snakePicks(168, 12));

        DraftSyncService.PollReport report = service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.COMPLETE);
        assertThat(report.totalPicks()).isEqualTo(168);
        assertThat(report.shortfall()).isZero();
        verify(writer).markComplete(session);
    }

    @Test
    @DisplayName("complete + picks at 166/168 -> SYNCING, shortfall 2, no markComplete, the 166 still inserted")
    void completeShortfallTwo() {
        SleeperDraft complete = draft("complete", "snake", 0, Map.of(USER_ID, 7), 12, 14);
        DraftSession session = session12x14(DraftStatus.ACTIVE);
        when(client.fetchDraft(DRAFT_ID)).thenReturn(complete);
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID)).thenReturn(Optional.of(session));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(snakePicks(166, 12));

        DraftSyncService.PollReport report = service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID);

        assertThat(report.status()).isEqualTo(DraftSyncStatus.SYNCING);
        assertThat(report.shortfall()).isEqualTo(2);
        verify(writer, never()).markComplete(any());
        // recovery data is never discarded: the short sweep still landed
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DraftPick>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertPicks(captor.capture());
        assertThat(captor.getValue()).hasSize(166);
    }

    @Test
    @DisplayName("complete + picks at 169/168 (> expected) -> halt naming both counts")
    void completeOvercountHalts() {
        SleeperDraft complete = draft("complete", "snake", 0, Map.of(USER_ID, 7), 12, 14);
        when(client.fetchDraft(DRAFT_ID)).thenReturn(complete);
        when(sessionRepository.findBySleeperDraftId(DRAFT_ID))
                .thenReturn(Optional.of(session12x14(DraftStatus.ACTIVE)));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(DRAFT_ID)).thenReturn(snakePicks(169, 12));

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(DRAFT_ID, CONFIG_ID, USER_ID))
                .withMessageContaining("169")
                .withMessageContaining("168");
        verify(writer, never()).markComplete(any());
    }

    // ----- the p5 anchor: one-shot import + snake cross-check over all 150 picks -----

    @Test
    @DisplayName("linking an already-complete draft = one-shot import; 150/150 pass the snake cross-check")
    void oneShotImportFullDraft() {
        SleeperDraft leagueDraft = fixture("draft-league-complete.json", SleeperDraft.class);
        String draftId = leagueDraft.draftId();
        when(client.fetchDraft(draftId)).thenReturn(leagueDraft);
        when(sessionRepository.findBySleeperDraftId(draftId)).thenReturn(Optional.empty());
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config(10)));
        writerAssignsId();
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(draftId)).thenReturn(pickFixture("picks-complete-150.json"));

        DraftSyncService.PollReport report = service().pollOnce(draftId, CONFIG_ID, USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DraftPick>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertPicks(captor.capture());
        List<DraftPick> rows = captor.getValue();
        assertThat(rows).hasSize(150);
        assertThat(rows).extracting(DraftPick::getOverallPickNo)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 150).boxed().toList());
        assertThat(report.status()).isEqualTo(DraftSyncStatus.COMPLETE);
        assertThat(report.totalPicks()).isEqualTo(150);
        verify(writer).markComplete(any(DraftSession.class));
    }

    @Test
    @DisplayName("mutated draft_slot in the p5 array -> halt with pick_no, both values, traded-pick hint")
    void snakeCrossCheckMutation() {
        SleeperDraft leagueDraft = fixture("draft-league-complete.json", SleeperDraft.class);
        String draftId = leagueDraft.draftId();
        List<SleeperDraftPick> picks = new ArrayList<>(pickFixture("picks-complete-150.json"));
        SleeperDraftPick p37 = picks.get(36);   // pick 37: round 4 reversed -> slot 4
        picks.set(36, new SleeperDraftPick(p37.pickNo(), p37.round(), 5,
                p37.playerId(), p37.isKeeper(), p37.rosterId()));

        when(client.fetchDraft(draftId)).thenReturn(leagueDraft);
        when(sessionRepository.findBySleeperDraftId(draftId))
                .thenReturn(Optional.of(syncedSession(draftId, DraftStatus.ACTIVE)));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(client.fetchPicks(draftId)).thenReturn(picks);

        assertThatIllegalStateException()
                .isThrownBy(() -> service().pollOnce(draftId, CONFIG_ID, USER_ID))
                .withMessageContaining("pick 37")
                .withMessageContaining("slot 5")
                .withMessageContaining("slot 4")
                .withMessageContaining("traded pick?");
        verify(writer, never()).insertPicks(anyList());
    }
}
