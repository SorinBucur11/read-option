package app.readoption.player;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;
import java.util.stream.IntStream;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The find_player search semantics on the real container: partial match,
 * case-insensitivity, the 5-candidate cap, and the active filter. The fixture
 * makes the wrong answers <i>available</i> — an inactive namesake that a missing
 * active filter would return, and six cap-busting namesakes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("find_player search query — partial, case-insensitive, active-only, capped at 5")
class PlayerSearchQueryTest extends AbstractPostgresTest {

    @Autowired private PlayerRepository playerRepository;

    @BeforeEach
    void seedPlayers() {
        playerRepository.saveAll(List.of(
                player("A1", "Brandon Aiyuk", "WR", "SF", true),
                player("C1", "Brandin Cooks", "WR", null, true),
                player("X1", "Brandon Retired", "RB", null, false)));   // inactive namesake
        // Six active namesakes: one more than the cap.
        playerRepository.saveAll(IntStream.rangeClosed(1, 6)
                .mapToObj(i -> player("S" + i, "Smith Number" + i, "WR", "KC", true))
                .toList());
    }

    @Test
    @DisplayName("partial match is case-insensitive: 'aiyUK' finds Brandon Aiyuk")
    void partialCaseInsensitiveMatch() {
        List<Player> found = playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("aiyUK");

        assertThat(found).extracting(Player::getFullName).containsExactly("Brandon Aiyuk");
    }

    @Test
    @DisplayName("an ambiguous partial returns all active candidates, name-ordered")
    void ambiguousPartialReturnsCandidates() {
        List<Player> found = playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Brand");

        // 'Brand' also matches the inactive 'Brandon Retired' — the active filter drops it.
        assertThat(found).extracting(Player::getFullName)
                .containsExactly("Brandin Cooks", "Brandon Aiyuk");
    }

    @Test
    @DisplayName("six matches: the cap returns exactly 5")
    void capAtFive() {
        List<Player> found = playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Smith");

        assertThat(found).hasSize(5);
    }

    @Test
    @DisplayName("no match returns an empty list, not an error")
    void noMatchIsEmpty() {
        assertThat(playerRepository
                .findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc("Zorn"))
                .isEmpty();
    }
}
