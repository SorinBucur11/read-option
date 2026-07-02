package app.readoption.customization;

import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused on the Fix A blast radius: scoring numbers became {@link BigDecimal}, and
 * {@code Objects.equals} on BigDecimal is scale-sensitive — the guard must compare
 * by value or a model re-emitting {@code 4} as {@code 4.0} reads as drift.
 */
@DisplayName("RefineDriftGuard — BigDecimal scoring fields compare by value, not scale")
class RefineDriftGuardTest {

    private final RefineDriftGuard guard = new RefineDriftGuard();

    private static ParsedLeague league(String passingTd, String interception) {
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.HALF_PPR,
                passingTd == null ? null : new BigDecimal(passingTd),
                interception == null ? null : new BigDecimal(interception),
                false);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        return new ParsedLeague(new LeagueRulesSpec(scoring, roster, null), null);
    }

    @Test
    @DisplayName("a scale-only difference (4 vs 4.0) is not drift")
    void scaleOnlyDifferenceIsNotDrift() {
        assertThat(guard.diff(league("4", "-2"), league("4.0", "-2.00"))).isEmpty();
    }

    @Test
    @DisplayName("a real value change still surfaces as drift")
    void realChangeIsDrift() {
        assertThat(guard.diff(league("4", "-2"), league("6", "-2")))
                .anyMatch(issue -> issue.field().equals("scoring.passingTdPoints"));
    }

    @Test
    @DisplayName("null vs value still surfaces as drift")
    void nullToValueIsDrift() {
        assertThat(guard.diff(league(null, "-2"), league("4", "-2")))
                .anyMatch(issue -> issue.field().equals("scoring.passingTdPoints"));
    }
}
