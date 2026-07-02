package app.readoption.customization.validation;

import app.readoption.customization.DraftTactics;
import app.readoption.scoring.Position;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Object-level validation of the strategy-bound half of the parse — sibling to
 * {@link LeagueRulesValidator}; the validation layer mirrors the parse-target
 * authority split (rules validator for the engine-bound half, tactics validator
 * for the strategy-bound half).
 *
 * <p>Tactics are otherwise soft-validated (their consumer is an LLM), but
 * {@code earliestRoundByPosition} is the one tactics field with a mechanical
 * consumer — the Phase 4 draft gate reads the round — so a broken value is a
 * structural bug and surfaces as BLOCKING, with a value-bearing message the
 * annotation route can't give (the {@code tactics.*} prefix rule would demote
 * an annotation violation to ASSUMPTION).
 *
 * <p>The bound is the flat 1..30 only — deliberately not cross-checked against
 * the roster's draft length, which would couple this validator to the
 * engine-bound {@code RosterSpec} across the authority split.
 */
@Component
public class DraftTacticsValidator {

    private static final int MIN_DRAFT_ROUND = 1;
    private static final int MAX_DRAFT_ROUND = 30;

    /** Null-safe: null tactics OR null map ⇒ no issues. */
    public List<ValidationIssue> validate(DraftTactics tactics) {
        if (tactics == null || tactics.earliestRoundByPosition() == null) {
            return List.of();
        }
        List<ValidationIssue> issues = new ArrayList<>();
        for (Map.Entry<Position, Integer> entry : tactics.earliestRoundByPosition().entrySet()) {
            Integer round = entry.getValue();
            if (round == null || round < MIN_DRAFT_ROUND || round > MAX_DRAFT_ROUND) {
                issues.add(new ValidationIssue("tactics.earliestRoundByPosition",
                        IssueSeverity.BLOCKING,
                        "Earliest draft round for " + entry.getKey() + " (" + round
                                + ") must be between " + MIN_DRAFT_ROUND + " and " + MAX_DRAFT_ROUND + "."));
            }
        }
        return issues;
    }
}
