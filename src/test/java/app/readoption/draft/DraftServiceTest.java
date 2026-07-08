package app.readoption.draft;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigNotFoundException;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.player.PlayerNotFoundException;
import app.readoption.player.PlayerRepository;
import app.readoption.scoring.Position;
import app.readoption.team.NflTeam;
import app.readoption.team.NflTeamRepository;
import app.readoption.team.TeamContextService;
import app.readoption.team.TeamScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DraftService — session lifecycle, server-assigned sequencing, state view")
class DraftServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long CONFIG_ID = 42L;
    private static final int SEASON = 2026;

    @Mock private DraftSessionRepository sessionRepository;
    @Mock private DraftPickRepository pickRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private LeagueConfigRepository leagueConfigRepository;
    @Mock private NflTeamRepository nflTeamRepository;
    @Mock private TeamScheduleRepository teamScheduleRepository;

    /**
     * Real TeamContextService over mocked repositories: unstubbed lookups return
     * empty, so every fixture team degrades loudly — exactly the LEFT-JOIN posture
     * under test. Tests that want a real bye stub {@code nflTeamRepository}.
     */
    private DraftService service() {
        return new DraftService(sessionRepository, pickRepository, playerRepository,
                leagueConfigRepository,
                new TeamContextService(nflTeamRepository, teamScheduleRepository,
                        playerRepository), SEASON);
    }

    /** 1 QB / 2 RB / 2 WR / 1 TE / 1 FLEX(RB,WR) / 0 SF / 6 bench = 13 rounds. */
    private static LeagueConfig config() {
        return LeagueConfig.builder()
                .id(CONFIG_ID)
                .teamCount(10)
                .qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(1)
                .flexEligible(Set.of(Position.RB, Position.WR))
                .superflexSlots(0).benchSlots(6)
                .build();
    }

    private static DraftSession session(DraftStatus status) {
        return DraftSession.builder()
                .id(SESSION_ID).leagueConfigId(CONFIG_ID).season(SEASON)
                .teamCount(10).userSlot(8).totalRounds(13).status(status)
                .build();
    }

    // ----- startSession -----

    @Test
    @DisplayName("startSession snapshots teamCount and freezes totalRounds from the config")
    void startSessionComputesTotalRounds() {
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));
        when(sessionRepository.save(any(DraftSession.class))).thenAnswer(inv -> inv.getArgument(0));

        DraftSession saved = service().startSession(new StartDraftRequest(CONFIG_ID, 8));

        assertThat(saved.getTotalRounds()).isEqualTo(13);
        assertThat(saved.getStatus()).isEqualTo(DraftStatus.ACTIVE);
        assertThat(saved.getSeason()).isEqualTo(SEASON);
        assertThat(saved.getLeagueConfigId()).isEqualTo(CONFIG_ID);
        assertThat(saved.getTeamCount()).isEqualTo(10);
        assertThat(saved.getUserSlot()).isEqualTo(8);
    }

    @Test
    @DisplayName("startSession rejects a userSlot beyond the config's teamCount without persisting (400)")
    void startSessionRejectsSlotBeyondTeamCount() {
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));

        // the 10-team fixture: slot 11 only fails once the config's teamCount is known
        assertThatExceptionOfType(InvalidDraftRequestException.class)
                .isThrownBy(() -> service().startSession(new StartDraftRequest(CONFIG_ID, 11)));
        verify(sessionRepository, never()).save(any(DraftSession.class));
    }

    @Test
    @DisplayName("startSession with an unknown league config -> LeagueConfigNotFoundException (404)")
    void startSessionUnknownConfig() {
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(LeagueConfigNotFoundException.class)
                .isThrownBy(() -> service().startSession(new StartDraftRequest(CONFIG_ID, 8)));
    }

    // ----- recordPick -----

    @Test
    @DisplayName("recordPick assigns overallPickNo = max + 1 server-side and derives the team slot")
    void recordPickAssignsNextNumber() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(playerRepository.existsById("4866")).thenReturn(true);
        when(pickRepository.findBySessionIdAndPlayerId(SESSION_ID, "4866")).thenReturn(Optional.empty());
        when(pickRepository.findMaxOverallPickNo(SESSION_ID)).thenReturn(Optional.of(10));
        when(pickRepository.saveAndFlush(any(DraftPick.class))).thenAnswer(inv -> {
            DraftPick p = inv.getArgument(0);
            p.setPickedAt(java.time.LocalDateTime.now());
            return p;
        });

        DraftPickView view = service().recordPick(SESSION_ID, new RecordPickRequest("4866"));

        assertThat(view.overallPickNo()).isEqualTo(11);
        assertThat(view.round()).isEqualTo(2);
        assertThat(view.teamSlot()).isEqualTo(10);   // pick 11, T=10: the turn
        assertThat(view.playerId()).isEqualTo("4866");
    }

    @Test
    @DisplayName("the final pick flips the session to COMPLETE in the same transaction")
    void finalPickCompletesSession() {
        DraftSession session = session(DraftStatus.ACTIVE);
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(playerRepository.existsById("4866")).thenReturn(true);
        when(pickRepository.findBySessionIdAndPlayerId(SESSION_ID, "4866")).thenReturn(Optional.empty());
        when(pickRepository.findMaxOverallPickNo(SESSION_ID)).thenReturn(Optional.of(129));
        when(pickRepository.saveAndFlush(any(DraftPick.class))).thenAnswer(inv -> inv.getArgument(0));

        DraftPickView view = service().recordPick(SESSION_ID, new RecordPickRequest("4866"));

        assertThat(view.overallPickNo()).isEqualTo(130);   // 10 teams x 13 rounds
        assertThat(session.getStatus()).isEqualTo(DraftStatus.COMPLETE);
    }

    @Test
    @DisplayName("unknown session -> DraftSessionNotFoundException (404)")
    void recordPickUnknownSession() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(DraftSessionNotFoundException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("4866")));
    }

    @Test
    @DisplayName("pick against a COMPLETE session -> DraftSessionNotActiveException (409)")
    void recordPickOnCompleteSession() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.COMPLETE)));

        assertThatExceptionOfType(DraftSessionNotActiveException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("4866")));
    }

    @Test
    @DisplayName("unknown player -> PlayerNotFoundException (404)")
    void recordPickUnknownPlayer() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(playerRepository.existsById("NOPE")).thenReturn(false);

        assertThatExceptionOfType(PlayerNotFoundException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("NOPE")));
    }

    @Test
    @DisplayName("already-drafted player -> 409 carrying the pick that took him")
    void recordPickDuplicatePlayer() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(playerRepository.existsById("4866")).thenReturn(true);
        when(pickRepository.findBySessionIdAndPlayerId(SESSION_ID, "4866")).thenReturn(Optional.of(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(3).playerId("4866").build()));

        assertThatExceptionOfType(PlayerAlreadyDraftedException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("4866")))
                .satisfies(ex -> {
                    assertThat(ex.getPlayerId()).isEqualTo("4866");
                    assertThat(ex.getOverallPickNo()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("a uq_draft_pick_player violation at flush translates to the same 409, not a 500")
    void constraintBackstopTranslatesTo409() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(playerRepository.existsById("4866")).thenReturn(true);
        when(pickRepository.findBySessionIdAndPlayerId(SESSION_ID, "4866")).thenReturn(Optional.empty());
        when(pickRepository.findMaxOverallPickNo(SESSION_ID)).thenReturn(Optional.of(4));
        when(pickRepository.saveAndFlush(any(DraftPick.class))).thenThrow(
                integrityViolation("uq_draft_pick_player"));

        assertThatExceptionOfType(PlayerAlreadyDraftedException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("4866")))
                .satisfies(ex -> assertThat(ex.getOverallPickNo()).isNull());
    }

    @Test
    @DisplayName("a different constraint (the concurrent-pick PK collision) is NOT swallowed into a 409")
    void unrelatedViolationRethrown() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(playerRepository.existsById("4866")).thenReturn(true);
        when(pickRepository.findBySessionIdAndPlayerId(SESSION_ID, "4866")).thenReturn(Optional.empty());
        when(pickRepository.findMaxOverallPickNo(SESSION_ID)).thenReturn(Optional.of(4));
        when(pickRepository.saveAndFlush(any(DraftPick.class))).thenThrow(
                integrityViolation("draft_pick_pkey"));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> service().recordPick(SESSION_ID, new RecordPickRequest("4866")));
    }

    /** Spring wrapper around Hibernate's typed violation, the shape the real dialect produces. */
    private static DataIntegrityViolationException integrityViolation(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("duplicate key value violates unique constraint",
                        new SQLException("23505"), constraintName));
    }

    // ----- getState -----

    /** The acceptance fixture: 10 teams, user slot 8, first 8 picks made. */
    private void stubEightPickState() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));

        List<DraftPick> picks = new ArrayList<>();
        IntStream.rangeClosed(1, 8).forEach(i -> picks.add(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(i).playerId("P" + i).build()));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(picks);
        when(playerRepository.findAllById(any())).thenReturn(List.of(
                player("P1", "Runner One", "RB"), player("P2", "Runner Two", "RB"),
                player("P3", "Catcher Three", "WR"), player("P4", "Runner Four", "RB"),
                player("P5", "Catcher Five", "WR"), player("P6", "Thrower Six", "QB"),
                player("P7", "End Seven", "TE"), player("P8", "Runner Eight", "RB")));
    }

    @Test
    @DisplayName("after 8 of 10 picks: pick 9 on the clock (slot 9), user waits 4 picks")
    void stateAfterEightPicks() {
        stubEightPickState();

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.currentOverallPick()).isEqualTo(9);
        assertThat(state.currentTeamSlot()).isEqualTo(9);
        assertThat(state.onTheClock()).isFalse();
        assertThat(state.picksUntilUserNextTurn()).isEqualTo(4);
    }

    @Test
    @DisplayName("gapTeams: slots 9 and 10 each pick twice before the user's next turn, counts only")
    void gapTeamsFixture() {
        stubEightPickState();

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.gapTeams()).hasSize(2);
        assertThat(state.gapTeams().get(0).teamSlot()).isEqualTo(9);
        assertThat(state.gapTeams().get(0).picksInGap()).isEqualTo(2);
        assertThat(state.gapTeams().get(0).positionalCounts()).isEmpty();   // no picks yet
        assertThat(state.gapTeams().get(1).teamSlot()).isEqualTo(10);
        assertThat(state.gapTeams().get(1).picksInGap()).isEqualTo(2);
    }

    @Test
    @DisplayName("userRoster carries name, position, and round; unfilledSlots decrements the RB slot")
    void userRosterAndUnfilledSlots() {
        stubEightPickState();

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.userRoster()).hasSize(1);
        assertThat(state.userRoster().get(0).playerId()).isEqualTo("P8");
        assertThat(state.userRoster().get(0).name()).isEqualTo("Runner Eight");
        assertThat(state.userRoster().get(0).position()).isEqualTo("RB");
        assertThat(state.userRoster().get(0).round()).isEqualTo(1);
        // 1 of 2 RB slots taken; SUPERFLEX absent (0 configured)
        assertThat(state.unfilledSlots()).containsEntry("RB", 1)
                .containsEntry("QB", 1).containsEntry("WR", 2).containsEntry("TE", 1)
                .containsEntry("FLEX", 1).containsEntry("BENCH", 6)
                .doesNotContainKey("SUPERFLEX");
    }

    @Test
    @DisplayName("a third RB overflows the dedicated slots into FLEX; a fourth lands on BENCH")
    void flexAbsorbsRosterOverflow() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(
                DraftSession.builder().id(SESSION_ID).leagueConfigId(CONFIG_ID).season(SEASON)
                        .teamCount(10).userSlot(1).totalRounds(13).status(DraftStatus.ACTIVE).build()));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));
        // slot 1 owns picks 1, 20, 21, 40 in a T=10 snake
        List<DraftPick> picks = List.of(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(1).playerId("R1").build(),
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(20).playerId("R2").build(),
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(21).playerId("R3").build(),
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(40).playerId("R4").build());
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(picks);
        when(playerRepository.findAllById(any())).thenReturn(List.of(
                player("R1", "RB One", "RB"), player("R2", "RB Two", "RB"),
                player("R3", "RB Three", "RB"), player("R4", "RB Four", "RB")));

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.userRoster()).hasSize(4);
        assertThat(state.unfilledSlots()).containsEntry("RB", 0)
                .containsEntry("FLEX", 0)      // third RB absorbed
                .containsEntry("BENCH", 5);    // fourth RB on the bench
    }

    @Test
    @DisplayName("roster byeWeek: known team reads nfl_team's derived bye")
    void rosterByeEnrichment() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.ACTIVE)));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));
        // Pick 8 belongs to user slot 8 in a 10-team snake.
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(8).playerId("P8").build()));
        when(playerRepository.findAllById(any())).thenReturn(List.of(
                player("P8", "Runner Eight", "RB", "PHI", true)));
        when(nflTeamRepository.findAllById(Set.of("PHI"))).thenReturn(List.of(
                NflTeam.builder().abbrev("PHI").espnAbbrev("PHI")
                        .name("Philadelphia Eagles").byeWeek(9).build()));

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.userRoster().get(0).byeWeek()).isEqualTo("9");
    }

    @Test
    @DisplayName("roster byeWeek degrades to the loud label for an unknown team — entry never dropped")
    void rosterByeDegradation() {
        stubEightPickState();   // fixture team "XX" is not in nfl_team

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.userRoster()).hasSize(1);
        assertThat(state.userRoster().get(0).byeWeek())
                .isEqualTo(TeamContextService.BYE_UNKNOWN_NO_TEAM);
    }

    @Test
    @DisplayName("a COMPLETE session has no current pick, no clock, and no gap")
    void completeSessionState() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session(DraftStatus.COMPLETE)));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(playerRepository.findAllById(any())).thenReturn(List.of());

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.currentOverallPick()).isNull();
        assertThat(state.currentTeamSlot()).isNull();
        assertThat(state.onTheClock()).isFalse();
        assertThat(state.picksUntilUserNextTurn()).isNull();
        assertThat(state.gapTeams()).isEmpty();
    }

    @Test
    @DisplayName("fresh session, user slot 1: on the clock at pick 1 with zero picks to wait")
    void freshSessionUserOnClock() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(
                DraftSession.builder().id(SESSION_ID).leagueConfigId(CONFIG_ID).season(SEASON)
                        .teamCount(10).userSlot(1).totalRounds(13).status(DraftStatus.ACTIVE).build()));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(config()));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
        when(playerRepository.findAllById(any())).thenReturn(List.of());

        DraftStateView state = service().getState(SESSION_ID);

        assertThat(state.currentOverallPick()).isEqualTo(1);
        assertThat(state.currentTeamSlot()).isEqualTo(1);
        assertThat(state.onTheClock()).isTrue();
        assertThat(state.picksUntilUserNextTurn()).isZero();
        assertThat(state.gapTeams()).isEmpty();
        assertThat(state.unfilledSlots()).containsEntry("RB", 2);
    }
}
