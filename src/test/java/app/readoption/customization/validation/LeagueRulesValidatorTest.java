package app.readoption.customization.validation;

import app.readoption.customization.LeagueRulesSpec;
import app.readoption.customization.PlayoffSpec;
import app.readoption.customization.RosterSpec;
import app.readoption.customization.ScoringSpec;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit. Messages are asserted for their values, not just their presence —
 * they feed the repair prompt, and a message that doesn't name the offending
 * values can't drive a one-turn fix.
 */
@DisplayName("LeagueRulesValidator — issue kinds, severities, collect-all behavior")
class LeagueRulesValidatorTest {

    private final LeagueRulesValidator validator = new LeagueRulesValidator();

    private static ScoringSpec fullyStatedScoring() {
        return new ScoringSpec(ReceptionFormat.PPR, new BigDecimal("4"), new BigDecimal("-2"), false);
    }

    private static RosterSpec roster(int teamCount, Set<Position> flexEligible) {
        return new RosterSpec(teamCount, 1, 2, 2, 1, 1, flexEligible, 0, 6);
    }

    private static RosterSpec roster() {
        return roster(12, Set.of(Position.RB, Position.WR, Position.TE));
    }

    @Test
    @DisplayName("a fully-stated, consistent spec yields no issues")
    void cleanSpecYieldsNoIssues() {
        List<ValidationIssue> issues = validator.validate(
                new LeagueRulesSpec(fullyStatedScoring(), roster(), new PlayoffSpec(6, 15, 17)));

        assertThat(issues).isEmpty();
    }

    @Test
    @DisplayName("null basePreset is BLOCKING — config has no safe default")
    void nullBasePresetBlocks() {
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                new ScoringSpec(null, new BigDecimal("4"), new BigDecimal("-2"), false),
                roster(), null));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).field()).isEqualTo("scoring.basePreset");
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("no safe default");
    }

    @Test
    @DisplayName("null passingTdPoints is ASSUMPTION, not BLOCKING")
    void nullPassingTdIsAssumption() {
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                new ScoringSpec(ReceptionFormat.PPR, null, new BigDecimal("-2"), false),
                roster(), null));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).field()).isEqualTo("scoring.passingTdPoints");
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.ASSUMPTION);
    }

    @Test
    @DisplayName("more playoff teams than league teams is BLOCKING, message names both values")
    void playoffTeamsExceedLeagueSize() {
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                fullyStatedScoring(), roster(6, Set.of(Position.RB, Position.WR)),
                new PlayoffSpec(8, 15, 17)));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).field()).isEqualTo("playoff.playoffTeams");
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("(8)").contains("(6)");
    }

    @Test
    @DisplayName("playoff end week before start week is BLOCKING")
    void playoffEndBeforeStart() {
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                fullyStatedScoring(), roster(), new PlayoffSpec(6, 17, 15)));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).field()).isEqualTo("playoff.playoffEndWeek");
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("(15)").contains("(17)");
    }

    @Test
    @DisplayName("flex eligibility outside RB/WR/TE is BLOCKING")
    void flexEligibleOutsideAllowedSet() {
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                fullyStatedScoring(), roster(12, Set.of(Position.QB, Position.RB)), null));

        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).field()).isEqualTo("roster.flexEligible");
        assertThat(issues.get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
        assertThat(issues.get(0).message()).contains("QB");
    }

    @Test
    @DisplayName("collects every issue in one pass — the repair prompt needs all of them at once")
    void collectsAllIssues() {
        // Null preset + oversubscribed playoffs + inverted weeks + bad flex, together.
        List<ValidationIssue> issues = validator.validate(new LeagueRulesSpec(
                new ScoringSpec(null, null, null, false),
                roster(4, Set.of(Position.QB)),
                new PlayoffSpec(6, 17, 15)));

        assertThat(issues).extracting(ValidationIssue::field).containsExactlyInAnyOrder(
                "scoring.basePreset",
                "scoring.passingTdPoints",
                "playoff.playoffTeams",
                "playoff.playoffEndWeek",
                "roster.flexEligible");
    }

    @Test
    @DisplayName("null containers are tolerated — their absence is the annotation pass's finding")
    void nullContainersTolerated() {
        assertThat(validator.validate(new LeagueRulesSpec(null, null, null))).isEmpty();
    }
}
