package app.readoption.draft;

import java.util.OptionalInt;

/**
 * Pure snake-draft pick arithmetic — no Spring, no I/O, all static (the
 * {@code DispersionCalculator} idiom). Everything is <b>1-based</b>: picks,
 * rounds, and team slots all start at 1.
 *
 * <p>This is the substrate for opponent-roster reconstruction in the draft state
 * view: there is deliberately no persisted {@code team_no}, so every team
 * assignment in the system routes through {@link #teamFor}.
 */
public final class SnakeOrder {

    private SnakeOrder() {
    }

    /** Round of overall pick {@code p}: {@code ceil(p / teamCount)}. */
    public static int roundOf(int overallPickNo, int teamCount) {
        requirePositive(overallPickNo, "overallPickNo");
        requirePositive(teamCount, "teamCount");
        return (overallPickNo + teamCount - 1) / teamCount;
    }

    /** Team slot that owns overall pick {@code p}: forward on odd rounds, reversed on even. */
    public static int teamFor(int overallPickNo, int teamCount) {
        int round = roundOf(overallPickNo, teamCount);
        int indexInRound = overallPickNo - (round - 1) * teamCount;
        return isOdd(round) ? indexInRound : teamCount - indexInRound + 1;
    }

    /** Overall pick number of {@code slot}'s pick in {@code round}. */
    public static int overallPickFor(int slot, int round, int teamCount) {
        requirePositive(round, "round");
        requireSlot(slot, teamCount);
        int base = (round - 1) * teamCount;
        return isOdd(round) ? base + slot : base + (teamCount - slot + 1);
    }

    /**
     * The smallest overall pick number strictly greater than {@code currentPick}
     * belonging to {@code slot}; empty if none remains in the draft.
     * {@code currentPick} may be 0 (no picks made yet).
     */
    public static OptionalInt nextPickFor(int slot, int currentPick, int teamCount, int totalRounds) {
        requireSlot(slot, teamCount);
        requirePositive(totalRounds, "totalRounds");
        if (currentPick < 0) {
            throw new IllegalArgumentException("currentPick must be >= 0, was " + currentPick);
        }
        // A slot owns exactly one pick per round; scan from the current round forward.
        int startRound = currentPick == 0 ? 1 : roundOf(currentPick, teamCount);
        for (int round = startRound; round <= totalRounds; round++) {
            int pick = overallPickFor(slot, round, teamCount);
            if (pick > currentPick) {
                return OptionalInt.of(pick);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * How many <i>other</i> teams' picks fall strictly between {@code currentPick}
     * and {@code slot}'s next pick; empty when the slot has no pick remaining.
     * Zero means the slot is on the clock.
     */
    public static OptionalInt picksUntilNextTurn(int slot, int currentPick, int teamCount, int totalRounds) {
        OptionalInt next = nextPickFor(slot, currentPick, teamCount, totalRounds);
        if (next.isEmpty()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(next.getAsInt() - currentPick - 1);
    }

    private static boolean isOdd(int round) {
        return round % 2 == 1;
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1, was " + value);
        }
    }

    private static void requireSlot(int slot, int teamCount) {
        requirePositive(teamCount, "teamCount");
        if (slot < 1 || slot > teamCount) {
            throw new IllegalArgumentException(
                    "slot must be between 1 and " + teamCount + ", was " + slot);
        }
    }
}
