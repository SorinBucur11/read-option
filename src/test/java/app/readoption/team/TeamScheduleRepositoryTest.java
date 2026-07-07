package app.readoption.team;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The writer's delete-and-reload against the real composite PK — a re-sync lands
 * rows with the SAME primary keys, which is exactly where Hibernate's
 * insert-before-delete flush order would collide if the delete weren't a bulk
 * statement. Proven here on the real container, not assumed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("team_schedule persistence — bulk-insert Persistable path, re-sync replaces in place")
class TeamScheduleRepositoryTest extends AbstractPostgresTest {

    @Autowired private TeamScheduleRepository scheduleRepository;
    @Autowired private NflTeamRepository nflTeamRepository;
    @Autowired private TestEntityManager entityManager;

    private TeamScheduleWriter writer() {
        return new TeamScheduleWriter(scheduleRepository, nflTeamRepository);
    }

    private static List<TeamSchedule> seventeenWeeks(String team, int season, int byeWeek,
                                                     String opponent) {
        return IntStream.rangeClosed(1, 18)
                .filter(week -> week != byeWeek)
                .mapToObj(week -> TeamSchedule.builder()
                        .team(team).season(season).week(week)
                        .opponent(opponent).isHome(week % 2 == 0)
                        .build())
                .toList();
    }

    @Test
    @DisplayName("re-sync with identical (team, season, week) PKs replaces rows and rewrites the bye")
    void reSyncReplacesInPlace() {
        TeamScheduleWriter writer = writer();

        writer.replace("KC", 2026, seventeenWeeks("KC", 2026, 9, "DEN"), 9);
        entityManager.flush();

        // Second sync: same PKs, different opponent and bye — the poisoned-flush case.
        writer.replace("KC", 2026, seventeenWeeks("KC", 2026, 10, "LV"), 10);
        entityManager.flush();
        entityManager.clear();

        List<TeamSchedule> rows = scheduleRepository.findAll().stream()
                .filter(row -> row.getTeam().equals("KC") && row.getSeason() == 2026)
                .toList();
        assertThat(rows).hasSize(17);
        assertThat(rows).allSatisfy(row -> assertThat(row.getOpponent()).isEqualTo("LV"));
        assertThat(rows).noneMatch(row -> row.getWeek() == 10);

        NflTeam kc = nflTeamRepository.findById("KC").orElseThrow();
        assertThat(kc.getByeWeek()).isEqualTo(10);
        assertThat(kc.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("replace scopes the delete to (team, season): other teams and seasons survive")
    void replaceScopedToTeamAndSeason() {
        TeamScheduleWriter writer = writer();
        writer.replace("KC", 2025, seventeenWeeks("KC", 2025, 6, "DEN"), 6);
        writer.replace("DEN", 2026, seventeenWeeks("DEN", 2026, 7, "KC"), 7);
        entityManager.flush();

        writer.replace("KC", 2026, seventeenWeeks("KC", 2026, 9, "LV"), 9);
        entityManager.flush();
        entityManager.clear();

        assertThat(scheduleRepository.findAll())
                .filteredOn(row -> row.getTeam().equals("KC") && row.getSeason() == 2025)
                .hasSize(17);
        assertThat(scheduleRepository.findAll())
                .filteredOn(row -> row.getTeam().equals("DEN"))
                .hasSize(17);
    }

    @Test
    @DisplayName("early-opponents query: weeks 1-3 only, ascending")
    void earlyWeeksQuery() {
        scheduleRepository.saveAll(seventeenWeeks("KC", 2026, 2, "DEN"));   // bye week 2!
        entityManager.flush();

        List<TeamSchedule> early = scheduleRepository
                .findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("KC", 2026, 3);

        assertThat(early).extracting(TeamSchedule::getWeek).containsExactly(1, 3);
    }
}
