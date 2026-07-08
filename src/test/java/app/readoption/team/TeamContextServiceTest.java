package app.readoption.team;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The degradation vocabulary both LLM-facing views speak. LEFT-JOIN posture:
 * null team, unknown team (stale OAK), missing bye, and unsynced schedule each
 * produce a distinct LOUD string — never a dropped row, never a silent remap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamContextService — loud degradation everywhere player→team is joined")
class TeamContextServiceTest {

    private static final int SEASON = 2026;

    @Mock private NflTeamRepository nflTeamRepository;
    @Mock private TeamScheduleRepository scheduleRepository;
    @Mock private PlayerRepository playerRepository;

    private TeamContextService service() {
        return new TeamContextService(nflTeamRepository, scheduleRepository, playerRepository);
    }

    private static NflTeam kc(Integer byeWeek) {
        return NflTeam.builder().abbrev("KC").espnAbbrev("KC")
                .name("Kansas City Chiefs").byeWeek(byeWeek).build();
    }

    @Test
    @DisplayName("known team: bye as string, opponents formatted 'W1 vs DEN (home)' / 'W2 at LV (away)'")
    void knownTeamContext() {
        when(nflTeamRepository.findById("KC")).thenReturn(Optional.of(kc(10)));
        when(scheduleRepository.findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("KC", SEASON, 3))
                .thenReturn(List.of(
                        TeamSchedule.builder().team("KC").season(SEASON).week(1)
                                .opponent("DEN").isHome(true).build(),
                        TeamSchedule.builder().team("KC").season(SEASON).week(2)
                                .opponent("LV").isHome(false).build()));

        TeamContextService.TeamContext context = service().contextFor("KC", SEASON);

        assertThat(context.byeWeek()).isEqualTo("10");
        assertThat(context.earlyOpponents())
                .containsExactly("W1 vs DEN (home)", "W2 at LV (away)");
    }

    @Test
    @DisplayName("null team: both facts degrade, no lookup attempted")
    void nullTeam() {
        TeamContextService.TeamContext context = service().contextFor(null, SEASON);

        assertThat(context.byeWeek()).isEqualTo(TeamContextService.BYE_UNKNOWN_NO_TEAM);
        assertThat(context.earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_UNAVAILABLE);
        verifyNoInteractions(nflTeamRepository, scheduleRepository);
    }

    @Test
    @DisplayName("unknown team (stale OAK): degradation, never a remap")
    void unknownTeam() {
        when(nflTeamRepository.findById("OAK")).thenReturn(Optional.empty());

        TeamContextService.TeamContext context = service().contextFor("OAK", SEASON);

        assertThat(context.byeWeek()).isEqualTo(TeamContextService.BYE_UNKNOWN_NO_TEAM);
        assertThat(context.earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_UNAVAILABLE);
        verifyNoInteractions(scheduleRepository);
    }

    @Test
    @DisplayName("known team, nothing synced: bye unknown + schedule-not-synced — distinct from no-team")
    void knownTeamNotSynced() {
        when(nflTeamRepository.findById("KC")).thenReturn(Optional.of(kc(null)));
        when(scheduleRepository.findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("KC", SEASON, 3))
                .thenReturn(List.of());

        TeamContextService.TeamContext context = service().contextFor("KC", SEASON);

        assertThat(context.byeWeek()).isEqualTo(TeamContextService.BYE_UNKNOWN);
        assertThat(context.earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_NOT_SYNCED);
    }

    // ----- teamRoom + the ladder map (4.3.1 Commit F) -----

    @Test
    @DisplayName("ladder map: the four fantasy positions, WR spanning exactly LWR/RWR/SWR")
    void positionLadderMapCompleteness() {
        assertThat(TeamContextService.POSITION_LADDERS.keySet())
                .containsExactlyInAnyOrder("QB", "RB", "WR", "TE");
        assertThat(TeamContextService.POSITION_LADDERS.get("WR"))
                .containsExactlyInAnyOrder("LWR", "RWR", "SWR");
        assertThat(TeamContextService.POSITION_LADDERS.get("QB")).containsExactly("QB");
        assertThat(TeamContextService.POSITION_LADDERS.get("RB")).containsExactly("RB");
        assertThat(TeamContextService.POSITION_LADDERS.get("TE")).containsExactly("TE");
    }

    @Test
    @DisplayName("teamRoom WR: the query is scoped to the three receiver ladders")
    void teamRoomScopesLaddersByFilter() {
        when(nflTeamRepository.findById("KC")).thenReturn(Optional.of(kc(10)));
        when(scheduleRepository.findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("KC", SEASON, 3))
                .thenReturn(List.of());
        Player receiver = player("W1", "Slot Guy", "WR", "KC", true);
        when(playerRepository
                .findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
                        "KC", Set.of("LWR", "RWR", "SWR")))
                .thenReturn(List.of(receiver));

        Optional<TeamContextService.TeamRoom> room = service().teamRoom("KC", "WR", SEASON);

        assertThat(room).isPresent();
        assertThat(room.get().players()).containsExactly(receiver);
        assertThat(room.get().byeWeek()).isEqualTo("10");
        assertThat(room.get().earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_NOT_SYNCED);
    }

    @Test
    @DisplayName("teamRoom without a filter spans all four fantasy ladders")
    void teamRoomNullFilterSpansAllLadders() {
        when(nflTeamRepository.findById("KC")).thenReturn(Optional.of(kc(10)));
        when(scheduleRepository.findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("KC", SEASON, 3))
                .thenReturn(List.of());
        when(playerRepository
                .findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
                        eq("KC"), anySet()))
                .thenReturn(List.of());

        service().teamRoom("KC", null, SEASON);

        verify(playerRepository)
                .findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
                        "KC", Set.of("QB", "RB", "LWR", "RWR", "SWR", "TE"));
    }

    @Test
    @DisplayName("teamRoom for an unknown team: empty — the tool layer owns the loud string")
    void teamRoomUnknownTeamIsEmpty() {
        when(nflTeamRepository.findById("XYZ")).thenReturn(Optional.empty());

        assertThat(service().teamRoom("XYZ", "RB", SEASON)).isEmpty();
        verifyNoInteractions(playerRepository, scheduleRepository);
    }

    @Test
    @DisplayName("a position with no ladder (K) throws loudly, naming the valid filters")
    void unknownLadderPositionThrows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service().teamRoom("KC", "K", SEASON))
                .withMessageContaining("K")
                .withMessageContaining("QB, RB, WR, TE");
    }

    @Test
    @DisplayName("byeWeekLabels: one batch lookup, unknown teams degrade in place")
    void byeWeekLabelsBatch() {
        when(nflTeamRepository.findAllById(Set.of("KC", "OAK"))).thenReturn(List.of(kc(10)));

        Map<String, String> labels = service().byeWeekLabels(Set.of("KC", "OAK"));

        assertThat(labels).containsEntry("KC", "10")
                .containsEntry("OAK", TeamContextService.BYE_UNKNOWN_NO_TEAM);
    }
}
