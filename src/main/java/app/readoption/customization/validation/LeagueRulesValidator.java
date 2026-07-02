package app.readoption.customization.validation;

import app.readoption.customization.LeagueRulesSpec;
import app.readoption.customization.PlayoffSpec;
import app.readoption.customization.RosterSpec;
import app.readoption.customization.ScoringSpec;
import app.readoption.scoring.Position;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Object-level (cross-field) validation of the LLM's spec — runs after the Jakarta
 * annotation pass. Collects <b>all</b> issues rather than failing on the first,
 * because the output feeds the repair prompt: the model fixes everything in one
 * refine turn only if it sees everything at once.
 *
 * <p>Null containers are tolerated (their absence is the annotation pass's finding,
 * not ours); each rule guards what it dereferences.
 */
@Component
public class LeagueRulesValidator {

    private static final Set<Position> FLEX_ALLOWED = Set.of(Position.RB, Position.WR, Position.TE);

    public List<ValidationIssue> validate(LeagueRulesSpec spec) {
        List<ValidationIssue> issues = new ArrayList<>();
        ScoringSpec scoring = spec.scoring();
        RosterSpec roster = spec.roster();
        PlayoffSpec playoff = spec.playoff();

        if (scoring != null && scoring.basePreset() == null) {
            // Belt-and-braces with @NotNull: surfaced as an explicit, value-bearing
            // issue because it drives the repair prompt — config has no safe default.
            issues.add(new ValidationIssue("scoring.basePreset", IssueSeverity.BLOCKING,
                    "Reception scoring was not stated and has no safe default — "
                            + "is the league standard, half-PPR, or full PPR?"));
        }

        if (scoring != null && scoring.passingTdPoints() == null) {
            issues.add(new ValidationIssue("scoring.passingTdPoints", IssueSeverity.ASSUMPTION,
                    "Passing-TD points not stated — assuming the preset default (4 per passing TD)."));
        }

        if (playoff != null && roster != null && playoff.playoffTeams() > roster.teamCount()) {
            issues.add(new ValidationIssue("playoff.playoffTeams", IssueSeverity.BLOCKING,
                    "Playoff teams (" + playoff.playoffTeams() + ") cannot exceed league size ("
                            + roster.teamCount() + ")."));
        }

        if (playoff != null && playoff.playoffEndWeek() < playoff.playoffStartWeek()) {
            issues.add(new ValidationIssue("playoff.playoffEndWeek", IssueSeverity.BLOCKING,
                    "Playoff end week (" + playoff.playoffEndWeek() + ") is before the start week ("
                            + playoff.playoffStartWeek() + ")."));
        }

        if (roster != null && roster.flexEligible() != null
                && !FLEX_ALLOWED.containsAll(roster.flexEligible())) {
            issues.add(new ValidationIssue("roster.flexEligible", IssueSeverity.BLOCKING,
                    "Flex eligibility may only contain RB, WR, TE — got " + roster.flexEligible() + "."));
        }

        return issues;
    }
}
