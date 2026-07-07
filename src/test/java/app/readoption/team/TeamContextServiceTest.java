package app.readoption.team;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    private TeamContextService service() {
        return new TeamContextService(nflTeamRepository, scheduleRepository);
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

    @Test
    @DisplayName("byeWeekLabels: one batch lookup, unknown teams degrade in place")
    void byeWeekLabelsBatch() {
        when(nflTeamRepository.findAllById(Set.of("KC", "OAK"))).thenReturn(List.of(kc(10)));

        Map<String, String> labels = service().byeWeekLabels(Set.of("KC", "OAK"));

        assertThat(labels).containsEntry("KC", "10")
                .containsEntry("OAK", TeamContextService.BYE_UNKNOWN_NO_TEAM);
    }
}
