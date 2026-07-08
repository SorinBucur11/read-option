package app.readoption.player;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;
import java.util.Set;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The sub-position scoping proof (spec §5.4): a team fields an order-1 LWR, an
 * order-1 RWR, and an order-1 SWR <i>simultaneously</i> — the ladder is the RAW
 * {@code depth_chart_position}, not the normalized position. This test seeds
 * exactly that room and MUST fail under a normalized-WR-scoped query (which
 * would report the LWR and RWR as false competition for the SWR backup).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("depthChartAhead query — raw sub-position ladder, team-scoped, order-ranked")
class PlayerDepthChartQueryTest extends AbstractPostgresTest {

    @Autowired private PlayerRepository playerRepository;

    private static Player depthPlayer(String id, String fullName, String team,
                                      String depthPosition, int depthOrder) {
        Player fixture = player(id, fullName, "WR", team, true);
        fixture.setDepthChartPosition(depthPosition);
        fixture.setDepthChartOrder(depthOrder);
        return fixture;
    }

    @BeforeEach
    void seedWideReceiverRoom() {
        playerRepository.saveAll(List.of(
                depthPlayer("W1", "Left One", "CIN", "LWR", 1),
                depthPlayer("W2", "Right One", "CIN", "RWR", 1),
                depthPlayer("W3", "Slot One", "CIN", "SWR", 1),
                depthPlayer("W4", "Slot Two", "CIN", "SWR", 2),
                depthPlayer("W5", "Slot Three", "CIN", "SWR", 3),
                depthPlayer("W6", "Other Room Slot", "KC", "SWR", 1),
                depthPlayer("R1", "Back One", "CIN", "RB", 1)));   // adjacent room, same team
    }

    @Test
    @DisplayName("order-2 SWR sees ONLY the order-1 SWR — not the LWR/RWR starters, not other teams")
    void subPositionScoping() {
        List<Player> ahead = playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "CIN", "SWR", 2);

        assertThat(ahead).extracting(Player::getFullName).containsExactly("Slot One");
    }

    @Test
    @DisplayName("deeper backup: the whole ladder above, ascending by order")
    void ladderOrdering() {
        List<Player> ahead = playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "CIN", "SWR", 3);

        assertThat(ahead).extracting(Player::getFullName)
                .containsExactly("Slot One", "Slot Two");
    }

    @Test
    @DisplayName("the starter has nobody ahead")
    void starterHasEmptyLadder() {
        List<Player> ahead = playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "CIN", "SWR", 1);

        assertThat(ahead).isEmpty();
    }

    // ----- team-room query (4.3.1 Commit F): ladders IN-set, ladder-then-order -----

    @Test
    @DisplayName("WR room: all three receiver ladders in ladder-then-order sequence, no RB, no other team")
    void receiverRoomSpansThreeLadders() {
        List<Player> room = playerRepository
                .findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
                        "CIN", Set.of("LWR", "RWR", "SWR"));

        assertThat(room).extracting(Player::getFullName).containsExactly(
                "Left One", "Right One", "Slot One", "Slot Two", "Slot Three");
    }

    @Test
    @DisplayName("RB room: the receiver ladders are excluded")
    void backfieldRoomExcludesReceivers() {
        List<Player> room = playerRepository
                .findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
                        "CIN", Set.of("RB"));

        assertThat(room).extracting(Player::getFullName).containsExactly("Back One");
    }
}
