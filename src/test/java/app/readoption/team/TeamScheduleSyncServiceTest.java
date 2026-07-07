package app.readoption.team;

import app.readoption.espn.EspnScheduleClient;
import app.readoption.espn.EspnScheduleResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fixtures follow the spec's adversarial rule: each one makes the wrong answer
 * <i>available</i> (a preseason week that would collide, extra missing weeks a
 * naive bye pick would grab, an away slot an order-0 assumption would misread)
 * so the test proves it wasn't taken.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamScheduleSyncService — type-2 filter, crosswalk at the write boundary, loud bye derivation")
class TeamScheduleSyncServiceTest {

    private static final int SEASON = 2026;

    @Mock private EspnScheduleClient scheduleClient;
    @Mock private NflTeamRepository nflTeamRepository;
    @Mock private TeamScheduleWriter writer;

    @Captor private ArgumentCaptor<List<TeamSchedule>> rowsCaptor;
    @Captor private ArgumentCaptor<Integer> byeCaptor;

    private static NflTeam team(String abbrev, String espnAbbrev, String name) {
        return NflTeam.builder().abbrev(abbrev).espnAbbrev(espnAbbrev).name(name).build();
    }

    private static EspnScheduleResponse.Event event(int seasonType, int week,
                                                    String homeAbbrev, String awayAbbrev) {
        return new EspnScheduleResponse.Event(
                new EspnScheduleResponse.Week(week),
                new EspnScheduleResponse.SeasonType(seasonType),
                List.of(new EspnScheduleResponse.Competition(List.of(
                        new EspnScheduleResponse.Competitor("home",
                                new EspnScheduleResponse.Team(homeAbbrev)),
                        new EspnScheduleResponse.Competitor("away",
                                new EspnScheduleResponse.Team(awayAbbrev))))));
    }

    /** Seeds the crosswalk map and gives every team an empty schedule unless overridden. */
    private TeamScheduleSyncService service(NflTeam... teams) {
        when(nflTeamRepository.findAll()).thenReturn(List.of(teams));
        when(scheduleClient.fetchSchedule(anyString(), anyInt()))
                .thenReturn(new EspnScheduleResponse(List.of()));
        return new TeamScheduleSyncService(scheduleClient, nflTeamRepository, writer);
    }

    @Test
    @DisplayName("seasonType filter: preseason (type 1) week collides but only the type-2 event lands")
    void preseasonEventsFiltered() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"), team("DEN", "DEN", "Denver Broncos"));
        when(scheduleClient.fetchSchedule("KC", SEASON)).thenReturn(new EspnScheduleResponse(List.of(
                event(1, 1, "KC", "DEN"),     // preseason week 1 — must NOT land
                event(2, 1, "KC", "DEN"))));  // regular-season week 1

        service.sync(SEASON);

        verify(writer).replace(eq("KC"), eq(SEASON), rowsCaptor.capture(), any());
        assertThat(rowsCaptor.getValue()).hasSize(1);
        assertThat(rowsCaptor.getValue().get(0).getWeek()).isEqualTo(1);
        assertThat(rowsCaptor.getValue().get(0).getOpponent()).isEqualTo("DEN");
    }

    @Test
    @DisplayName("bye derivation: 17 rows, exactly week 9 missing -> bye = 9")
    void byeDerivedFromScheduleGap() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"), team("DEN", "DEN", "Denver Broncos"));
        List<EspnScheduleResponse.Event> events = new ArrayList<>();
        IntStream.rangeClosed(1, 18).filter(week -> week != 9)
                .forEach(week -> events.add(event(2, week, "KC", "DEN")));
        when(scheduleClient.fetchSchedule("KC", SEASON))
                .thenReturn(new EspnScheduleResponse(events));

        service.sync(SEASON);

        verify(writer).replace(eq("KC"), eq(SEASON), anyList(), byeCaptor.capture());
        assertThat(byeCaptor.getValue()).isEqualTo(9);
    }

    @Test
    @DisplayName("partial fetch (15 rows): bye = null — a plausible-but-unproven gap is never taken")
    void partialFetchDerivesNullBye() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"), team("DEN", "DEN", "Denver Broncos"));
        // Weeks 1..15 land; 16/17/18 are missing. Any of them is an available wrong
        // answer for a naive "the missing week is the bye" implementation.
        List<EspnScheduleResponse.Event> events = new ArrayList<>();
        IntStream.rangeClosed(1, 15)
                .forEach(week -> events.add(event(2, week, "KC", "DEN")));
        when(scheduleClient.fetchSchedule("KC", SEASON))
                .thenReturn(new EspnScheduleResponse(events));

        service.sync(SEASON);

        verify(writer).replace(eq("KC"), eq(SEASON), rowsCaptor.capture(), byeCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(15);   // rows still land
        assertThat(byeCaptor.getValue()).isNull();       // the bye does not
    }

    @Test
    @DisplayName("crosswalk: ESPN 'WSH' lands as Sleeper 'WAS' — one vocabulary on the read side")
    void opponentCrosswalkedAtWriteBoundary() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"),
                team("WAS", "WSH", "Washington Commanders"));
        when(scheduleClient.fetchSchedule("KC", SEASON)).thenReturn(new EspnScheduleResponse(
                List.of(event(2, 1, "KC", "WSH"))));

        service.sync(SEASON);

        verify(writer).replace(eq("KC"), eq(SEASON), rowsCaptor.capture(), any());
        assertThat(rowsCaptor.getValue().get(0).getOpponent()).isEqualTo("WAS");
    }

    @Test
    @DisplayName("unknown ESPN abbreviation: that team fails loudly, nothing lands, others unaffected")
    void unknownAbbrevFailsThatTeamLoudly() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"), team("DEN", "DEN", "Denver Broncos"));
        when(scheduleClient.fetchSchedule("KC", SEASON)).thenReturn(new EspnScheduleResponse(
                List.of(event(2, 1, "KC", "XYZ"))));

        TeamScheduleSyncService.TeamScheduleSyncReport report = service.sync(SEASON);

        verify(writer, never()).replace(eq("KC"), anyInt(), anyList(), any());
        assertThat(report.failed()).hasSize(1);
        assertThat(report.failed().get(0)).contains("KC").contains("XYZ");
        assertThat(report.skipped()).contains("DEN (no regular-season events)");
    }

    @Test
    @DisplayName("homeAway: our team in the AWAY slot — order-0 competitor assumptions would misread this")
    void awayCompetitorSelectedByAbbrevMatch() {
        TeamScheduleSyncService service = service(
                team("KC", "KC", "Kansas City Chiefs"), team("DEN", "DEN", "Denver Broncos"));
        // DEN is home (competitor 0), KC away (competitor 1).
        when(scheduleClient.fetchSchedule("KC", SEASON)).thenReturn(new EspnScheduleResponse(
                List.of(event(2, 5, "DEN", "KC"))));

        service.sync(SEASON);

        verify(writer).replace(eq("KC"), eq(SEASON), rowsCaptor.capture(), any());
        TeamSchedule row = rowsCaptor.getValue().get(0);
        assertThat(row.isHome()).isFalse();
        assertThat(row.getOpponent()).isEqualTo("DEN");
        assertThat(row.getTeam()).isEqualTo("KC");
    }

    @Test
    @DisplayName("empty schedule: team is skipped, existing rows never deleted")
    void emptyScheduleSkipsWithoutDeleting() {
        TeamScheduleSyncService service = service(team("KC", "KC", "Kansas City Chiefs"));

        TeamScheduleSyncService.TeamScheduleSyncReport report = service.sync(SEASON);

        verify(writer, never()).replace(anyString(), anyInt(), anyList(), any());
        assertThat(report.skipped()).containsExactly("KC (no regular-season events)");
        assertThat(report.synced()).isEmpty();
        assertThat(report.failed()).isEmpty();
    }
}
