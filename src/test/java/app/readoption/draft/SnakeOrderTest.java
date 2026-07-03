package app.readoption.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * SnakeOrder is the substrate for opponent-roster reconstruction — over-tested
 * deliberately: an exhaustive two-round table, the turn parity, the slot-8/T=10
 * gap fixture from the design discussion, and exhaustion.
 */
@DisplayName("SnakeOrder — pure 1-based snake-draft pick arithmetic")
class SnakeOrderTest {

    private static final int T10 = 10;

    @Nested
    @DisplayName("teamFor")
    class TeamFor {

        @Test
        @DisplayName("all 20 picks of a T=10 two-round table match the hand-written expectation")
        void exhaustiveTwoRoundTable() {
            int[] expectedSlotByPick = {
                    // round 1: forward
                    1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                    // round 2: reversed
                    10, 9, 8, 7, 6, 5, 4, 3, 2, 1
            };
            for (int pick = 1; pick <= 20; pick++) {
                assertThat(SnakeOrder.teamFor(pick, T10))
                        .as("pick %d", pick)
                        .isEqualTo(expectedSlotByPick[pick - 1]);
            }
        }

        @Test
        @DisplayName("parity at the turn: slot 10 owns picks 10 and 11; slot 1 owns 1 and 20")
        void parityAtTheTurn() {
            assertThat(SnakeOrder.teamFor(10, T10)).isEqualTo(10);
            assertThat(SnakeOrder.teamFor(11, T10)).isEqualTo(10);
            assertThat(SnakeOrder.teamFor(1, T10)).isEqualTo(1);
            assertThat(SnakeOrder.teamFor(20, T10)).isEqualTo(1);
        }

        @Test
        @DisplayName("T=12 spot checks across three rounds")
        void twelveTeamSpotChecks() {
            assertThat(SnakeOrder.teamFor(1, 12)).isEqualTo(1);
            assertThat(SnakeOrder.teamFor(12, 12)).isEqualTo(12);
            assertThat(SnakeOrder.teamFor(13, 12)).isEqualTo(12);
            assertThat(SnakeOrder.teamFor(24, 12)).isEqualTo(1);
            assertThat(SnakeOrder.teamFor(25, 12)).isEqualTo(1);
            assertThat(SnakeOrder.teamFor(30, 12)).isEqualTo(6);   // round 3 forward
        }
    }

    @Nested
    @DisplayName("roundOf")
    class RoundOf {

        @Test
        @DisplayName("round boundaries: picks 1/10/11/20/21 for T=10")
        void roundBoundaries() {
            assertThat(SnakeOrder.roundOf(1, T10)).isEqualTo(1);
            assertThat(SnakeOrder.roundOf(10, T10)).isEqualTo(1);
            assertThat(SnakeOrder.roundOf(11, T10)).isEqualTo(2);
            assertThat(SnakeOrder.roundOf(20, T10)).isEqualTo(2);
            assertThat(SnakeOrder.roundOf(21, T10)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("overallPickFor")
    class OverallPickFor {

        @Test
        @DisplayName("inverts teamFor over the whole T=10 two-round table")
        void invertsTeamFor() {
            for (int pick = 1; pick <= 20; pick++) {
                int round = SnakeOrder.roundOf(pick, T10);
                int slot = SnakeOrder.teamFor(pick, T10);
                assertThat(SnakeOrder.overallPickFor(slot, round, T10))
                        .as("pick %d", pick)
                        .isEqualTo(pick);
            }
        }

        @Test
        @DisplayName("odd round forward, even round reversed, for T=12")
        void twelveTeamRounds() {
            assertThat(SnakeOrder.overallPickFor(3, 1, 12)).isEqualTo(3);
            assertThat(SnakeOrder.overallPickFor(3, 2, 12)).isEqualTo(22);
            assertThat(SnakeOrder.overallPickFor(3, 3, 12)).isEqualTo(27);
        }
    }

    @Nested
    @DisplayName("nextPickFor / picksUntilNextTurn")
    class NextPick {

        @Test
        @DisplayName("slot 8, T=10: after pick 8 the next is 13; gap picks 9-12 belong to 9,10,10,9")
        void slotEightGapFixture() {
            assertThat(SnakeOrder.nextPickFor(8, 8, T10, 15)).isEqualTo(OptionalInt.of(13));
            assertThat(SnakeOrder.picksUntilNextTurn(8, 8, T10, 15)).isEqualTo(OptionalInt.of(4));
            assertThat(SnakeOrder.teamFor(9, T10)).isEqualTo(9);
            assertThat(SnakeOrder.teamFor(10, T10)).isEqualTo(10);
            assertThat(SnakeOrder.teamFor(11, T10)).isEqualTo(10);
            assertThat(SnakeOrder.teamFor(12, T10)).isEqualTo(9);
        }

        @Test
        @DisplayName("currentPick=0 (fresh draft): slot 1 is on the clock, slot 8's first pick is 8")
        void freshDraft() {
            assertThat(SnakeOrder.nextPickFor(1, 0, T10, 2)).isEqualTo(OptionalInt.of(1));
            assertThat(SnakeOrder.picksUntilNextTurn(1, 0, T10, 2)).isEqualTo(OptionalInt.of(0));
            assertThat(SnakeOrder.nextPickFor(8, 0, T10, 2)).isEqualTo(OptionalInt.of(8));
            assertThat(SnakeOrder.picksUntilNextTurn(8, 0, T10, 2)).isEqualTo(OptionalInt.of(7));
        }

        @Test
        @DisplayName("a slot whose current-round pick is still ahead is found in that same round")
        void nextPickInCurrentRound() {
            // after pick 3 in round 1, slot 7's round-1 pick (7) is still ahead
            assertThat(SnakeOrder.nextPickFor(7, 3, T10, 2)).isEqualTo(OptionalInt.of(7));
        }

        @Test
        @DisplayName("last-round exhaustion returns empty")
        void lastRoundExhaustion() {
            // two rounds, T=10: slot 1's last pick is 20
            assertThat(SnakeOrder.nextPickFor(1, 20, T10, 2)).isEmpty();
            assertThat(SnakeOrder.picksUntilNextTurn(1, 20, T10, 2)).isEmpty();
            // slot 10's last pick is 11; from pick 12 on it has nothing left
            assertThat(SnakeOrder.nextPickFor(10, 12, T10, 2)).isEmpty();
        }

        @Test
        @DisplayName("T=12 spot check: slot 12 owns the turn picks 12 and 13")
        void twelveTeamTurn() {
            assertThat(SnakeOrder.nextPickFor(12, 11, 12, 2)).isEqualTo(OptionalInt.of(12));
            assertThat(SnakeOrder.nextPickFor(12, 12, 12, 2)).isEqualTo(OptionalInt.of(13));
            assertThat(SnakeOrder.picksUntilNextTurn(12, 12, 12, 2)).isEqualTo(OptionalInt.of(0));
        }
    }

    @Nested
    @DisplayName("argument guards")
    class Guards {

        @Test
        @DisplayName("zero/negative picks, rounds, and out-of-range slots are rejected")
        void rejectsBadArguments() {
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.teamFor(0, T10));
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.roundOf(1, 0));
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.overallPickFor(11, 1, T10));
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.overallPickFor(0, 1, T10));
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.nextPickFor(1, -1, T10, 2));
            assertThatIllegalArgumentException().isThrownBy(() -> SnakeOrder.nextPickFor(1, 0, T10, 0));
        }
    }
}
