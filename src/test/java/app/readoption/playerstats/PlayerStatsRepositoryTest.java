package app.readoption.playerstats;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static app.readoption.TestFixtures.player;
import static app.readoption.TestFixtures.stat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("PlayerStatsRepository.findDistinctYears — real Postgres")
class PlayerStatsRepositoryTest extends AbstractPostgresTest {

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerStatsRepository playerStatsRepository;

    @BeforeEach
    void seed() {
        playerRepository.save(player("1", "Josh Allen", "QB"));
        playerRepository.save(player("2", "Saquon Barkley", "RB"));

        // Player 1 played in 2024 and 2025; player 2 played in 2024
        playerStatsRepository.save(stat("1", 2024));
        playerStatsRepository.save(stat("1", 2025));
        playerStatsRepository.save(stat("2", 2024));
    }

    @Test
    @DisplayName("returns distinct years in ascending order across all players")
    void findDistinctYearsReturnsOrderedDistinctYears() {
        List<Integer> years = playerStatsRepository.findDistinctYears();

        assertThat(years).containsExactly(2024, 2025);
    }

    @Test
    @DisplayName("adding a duplicate year does not create a new distinct year entry")
    void duplicateYearsAreCollapsed() {
        // player 2 already has 2024; player 1 adding 2026 should add a new distinct year
        playerRepository.save(player("3", "Ja'Marr Chase", "WR"));
        playerStatsRepository.save(stat("3", 2024));
        playerStatsRepository.save(stat("3", 2026));

        List<Integer> years = playerStatsRepository.findDistinctYears();

        assertThat(years).containsExactly(2024, 2025, 2026);
    }
}