package app.readoption.customization.validation;

import app.readoption.customization.DraftTactics;
import app.readoption.scoring.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DraftTacticsValidator — BLOCKING bound on earliestRoundByPosition")
class DraftTacticsValidatorTest {

    private final DraftTacticsValidator validator = new DraftTacticsValidator();

    private static DraftTactics tacticsWith(Map<Position, Integer> rounds) {
        return new DraftTactics(null, null, rounds, null);
    }

    @Test
    @DisplayName("a valid round yields no issues")
    void validRound() {
        assertThat(validator.validate(tacticsWith(Map.of(Position.QB, 10)))).isEmpty();
    }

    @Test
    @DisplayName("round 0 is BLOCKING with a value-bearing message")
    void roundBelowMinimum() {
        List<ValidationIssue> issues = validator.validate(tacticsWith(Map.of(Position.QB, 0)));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).field()).isEqualTo("tactics.earliestRoundByPosition");
        assertThat(issues.get(0).message()).contains("QB").contains("(0)").contains("between 1 and 30");
    }

    @Test
    @DisplayName("round 40 is BLOCKING")
    void roundAboveMaximum() {
        List<ValidationIssue> issues = validator.validate(tacticsWith(Map.of(Position.QB, 40)));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("QB").contains("(40)");
    }

    @Test
    @DisplayName("a null round is BLOCKING")
    void nullRound() {
        Map<Position, Integer> rounds = new HashMap<>();
        rounds.put(Position.QB, null);

        List<ValidationIssue> issues = validator.validate(tacticsWith(rounds));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("QB").contains("(null)");
    }

    @Test
    @DisplayName("each bad entry gets its own issue")
    void oneIssuePerBadEntry() {
        List<ValidationIssue> issues = validator.validate(
                tacticsWith(Map.of(Position.QB, 0, Position.TE, 40, Position.RB, 5)));

        assertThat(issues).hasSize(2);
        assertThat(issues).allMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
    }

    @Test
    @DisplayName("null tactics yields no issues")
    void nullTactics() {
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    @DisplayName("null map yields no issues")
    void nullMap() {
        assertThat(validator.validate(tacticsWith(null))).isEmpty();
    }
}
