package app.readoption.playerscoring;

import app.readoption.AbstractPostgresTest;
import app.readoption.TestFixtures;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.scoring.ScoringFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static app.readoption.TestFixtures.player;
import static app.readoption.TestFixtures.scoring;
import static app.readoption.scoring.ScoringFormat.STANDARD_6PT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)   // use the real container, not embedded H2
@DisplayName("PlayerScoringRepository.findLeaderboard — real Postgres")
class PlayerScoringRepositoryTest extends AbstractPostgresTest {

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerScoringRepository playerScoringRepository;

    @BeforeEach
    void seed() {
        playerRepository.save(player("1", "Josh Allen", "QB"));
        playerRepository.save(player("2", "Lamar Jackson", "QB"));
        playerRepository.save(player("3", "Saquon Barkley", "RB"));

        playerScoringRepository.save(scoring("1", 2026, STANDARD_6PT, "400.00"));
        playerScoringRepository.save(scoring("2", 2026, STANDARD_6PT, "380.00"));
        playerScoringRepository.save(scoring("3", 2026, STANDARD_6PT, "300.00"));
    }

    @Test
    @DisplayName("orders by points desc and joins the player name across unmapped entities")
    void ordersAndJoins() {
        Page<LeaderboardRow> page = playerScoringRepository.findLeaderboard(
                2026, STANDARD_6PT, null, null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(LeaderboardRow::fullName)
                .containsExactly("Josh Allen", "Lamar Jackson", "Saquon Barkley");   // 400 > 380 > 300
        assertThat(page.getContent().get(0).position()).isEqualTo("QB");
    }

    @Test
    @DisplayName("position filter narrows both the content and the count")
    void positionFilter() {
        Page<LeaderboardRow> page = playerScoringRepository.findLeaderboard(
                2026, STANDARD_6PT, "RB", null, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(LeaderboardRow::fullName).containsExactly("Saquon Barkley");
    }

    @Test
    @DisplayName("active=true excludes retired players; null includes them")
    void findLeaderboard_filtersByActive() {
        Player active = TestFixtures.player("4046", "Patrick Mahomes", "QB", "KC", true);
        Player retired = TestFixtures.player("99999", "Old Timer", "QB", "FA", false);
        playerRepository.saveAll(List.of(active, retired));

        playerScoringRepository.saveAll(List.of(
                TestFixtures.scoring("4046", 2026, ScoringFormat.STANDARD_6PT),
                TestFixtures.scoring("99999", 2026, ScoringFormat.STANDARD_6PT)));

        Pageable pageable = PageRequest.of(0, 25);

        Page<LeaderboardRow> all = playerScoringRepository.findLeaderboard(
                2026, ScoringFormat.STANDARD_6PT, null, null, pageable);
        Page<LeaderboardRow> activeOnly = playerScoringRepository.findLeaderboard(
                2026, ScoringFormat.STANDARD_6PT, null, true, pageable);

        // 99999 is the only inactive player, so the active filter drops exactly it
        assertThat(all.getContent()).extracting(LeaderboardRow::playerId).contains("4046", "99999");
        assertThat(activeOnly.getContent()).extracting(LeaderboardRow::playerId)
                .contains("4046").doesNotContain("99999");
        assertThat(activeOnly.getTotalElements()).isEqualTo(all.getTotalElements() - 1);
    }
}