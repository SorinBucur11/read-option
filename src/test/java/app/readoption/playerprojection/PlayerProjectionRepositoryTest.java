package app.readoption.playerprojection;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;

import static app.readoption.TestFixtures.player;
import static app.readoption.TestFixtures.projection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("PlayerProjectionRepository.countBetterAdpAtPosition — real Postgres")
class PlayerProjectionRepositoryTest extends AbstractPostgresTest {

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerProjectionRepository playerProjectionRepository;

    @BeforeEach
    void seed() {
        playerRepository.save(player("1", "P1", "QB"));
        playerProjectionRepository.save(projection("1", 2026, new BigDecimal("10.0"), new BigDecimal("12.0")));
        playerRepository.save(player("2", "P2", "QB"));
        playerProjectionRepository.save(projection("2", 2026, new BigDecimal("20.0"), new BigDecimal("22.0")));
        playerRepository.save(player("3", "P3", "QB"));
        playerProjectionRepository.save(projection("3", 2026, new BigDecimal("30.0"), new BigDecimal("33.0")));
        playerRepository.save(player("4", "P4", "QB"));
        playerProjectionRepository.save(projection("4", 2026, null, null));          // undrafted
        playerRepository.save(player("9", "P9", "RB"));
        playerProjectionRepository.save(projection("9", 2026, new BigDecimal("5.0"), new BigDecimal("5.0")));
    }

    @Test
    @DisplayName("counts same-position players with a strictly better ADP; null-ADP and other positions excluded")
    void countsBetterStandard() {
        // player at STANDARD ADP 30: QBs 10 and 20 are better; the null-ADP QB and the RB don't count
        long better = playerProjectionRepository.countBetterAdpAtPosition(
                2026, "QB", "STANDARD", new BigDecimal("30.0"));
        assertThat(better).isEqualTo(2);   // -> rank 3
    }

    @Test
    @DisplayName("searched CASE selects the PPR column for a PPR bucket")
    void usesPprColumn() {
        // PPR ADPs are 12, 22, 33; player at PPR 22 has exactly one better (12)
        long better = playerProjectionRepository.countBetterAdpAtPosition(
                2026, "QB", "PPR", new BigDecimal("22.0"));
        assertThat(better).isEqualTo(1);
    }
}