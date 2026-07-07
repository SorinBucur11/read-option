package app.readoption.team;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Empirical verification of the V14 seed on the real container — counts queried
 * from the table, not inferred from the migration file.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)   // real container, real V13/V14
@DisplayName("nfl_team seed — exactly 32 teams, WAS/WSH crosswalk, no OAK")
class NflTeamRepositoryTest extends AbstractPostgresTest {

    @Autowired private NflTeamRepository repository;

    @Test
    @DisplayName("exactly 32 rows seeded, byes start null")
    void thirtyTwoTeamsSeeded() {
        List<NflTeam> teams = repository.findAll();

        assertThat(teams).hasSize(32);
        assertThat(teams).allSatisfy(team -> {
            assertThat(team.getByeWeek()).isNull();
            assertThat(team.getCreatedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("WAS is the single crosswalk divergence (espn_abbrev WSH); all others match")
    void crosswalkDivergence() {
        NflTeam washington = repository.findById("WAS").orElseThrow();
        assertThat(washington.getEspnAbbrev()).isEqualTo("WSH");
        assertThat(washington.getName()).isEqualTo("Washington Commanders");

        assertThat(repository.findAll().stream()
                .filter(team -> !team.getAbbrev().equals(team.getEspnAbbrev()))
                .map(NflTeam::getAbbrev))
                .containsExactly("WAS");
    }

    @Test
    @DisplayName("OAK is deliberately absent — stale Sleeper code is source noise, not a team")
    void oakExcluded() {
        assertThat(repository.findById("OAK")).isEmpty();
        assertThat(repository.findById("LV")).isPresent();
    }
}
