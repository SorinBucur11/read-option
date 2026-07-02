package app.readoption.customization;

import app.readoption.customization.validation.IssueSeverity;
import app.readoption.customization.validation.ValidationIssue;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Detects refine drift: models "helpfully" rewrite fields the correction never
 * addressed. Carrying the typed prior object through the refine turn is what makes
 * this detectable — this guard diffs it against the returned object, field by field,
 * deterministically (no model involvement).
 *
 * <p>Whether a change was <i>asked for</i> is a semantic judgment this guard refuses
 * to make: every changed field is surfaced as an {@link IssueSeverity#ASSUMPTION}
 * issue naming both values, so the intended change reads as confirmation of the
 * correction and anything else reads as drift for the user to reject. ASSUMPTION,
 * not BLOCKING — drift must not dead-lock the repair loop.
 */
@Component
public class RefineDriftGuard {

    public List<ValidationIssue> diff(ParsedLeague before, ParsedLeague after) {
        List<ValidationIssue> changes = new ArrayList<>();

        ScoringSpec sb = before.rules() == null ? null : before.rules().scoring();
        ScoringSpec sa = after.rules() == null ? null : after.rules().scoring();
        compare(changes, "scoring.basePreset",
                sb == null ? null : sb.basePreset(), sa == null ? null : sa.basePreset());
        compareNumeric(changes, "scoring.passingTdPoints",
                sb == null ? null : sb.passingTdPoints(), sa == null ? null : sa.passingTdPoints());
        compareNumeric(changes, "scoring.interceptionPoints",
                sb == null ? null : sb.interceptionPoints(), sa == null ? null : sa.interceptionPoints());
        compare(changes, "scoring.tePremium",
                sb == null ? null : sb.tePremium(), sa == null ? null : sa.tePremium());

        RosterSpec rb = before.rules() == null ? null : before.rules().roster();
        RosterSpec ra = after.rules() == null ? null : after.rules().roster();
        compare(changes, "roster.teamCount",
                rb == null ? null : rb.teamCount(), ra == null ? null : ra.teamCount());
        compare(changes, "roster.qbSlots",
                rb == null ? null : rb.qbSlots(), ra == null ? null : ra.qbSlots());
        compare(changes, "roster.rbSlots",
                rb == null ? null : rb.rbSlots(), ra == null ? null : ra.rbSlots());
        compare(changes, "roster.wrSlots",
                rb == null ? null : rb.wrSlots(), ra == null ? null : ra.wrSlots());
        compare(changes, "roster.teSlots",
                rb == null ? null : rb.teSlots(), ra == null ? null : ra.teSlots());
        compare(changes, "roster.flexSlots",
                rb == null ? null : rb.flexSlots(), ra == null ? null : ra.flexSlots());
        compare(changes, "roster.flexEligible",
                rb == null ? null : rb.flexEligible(), ra == null ? null : ra.flexEligible());
        compare(changes, "roster.superflexSlots",
                rb == null ? null : rb.superflexSlots(), ra == null ? null : ra.superflexSlots());
        compare(changes, "roster.benchSlots",
                rb == null ? null : rb.benchSlots(), ra == null ? null : ra.benchSlots());

        // PlayoffSpec has only three scalar fields — record equality is the field diff.
        compare(changes, "playoff",
                before.rules() == null ? null : before.rules().playoff(),
                after.rules() == null ? null : after.rules().playoff());

        DraftTactics tb = before.tactics();
        DraftTactics ta = after.tactics();
        compare(changes, "tactics.positionalStrategy",
                tb == null ? null : tb.positionalStrategy(), ta == null ? null : ta.positionalStrategy());
        compare(changes, "tactics.riskPosture",
                tb == null ? null : tb.riskPosture(), ta == null ? null : ta.riskPosture());
        compare(changes, "tactics.earliestRoundByPosition",
                tb == null ? null : tb.earliestRoundByPosition(),
                ta == null ? null : ta.earliestRoundByPosition());
        compare(changes, "tactics.freeformNotes",
                tb == null ? null : tb.freeformNotes(), ta == null ? null : ta.freeformNotes());

        return changes;
    }

    private void compare(List<ValidationIssue> changes, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            addChange(changes, field, before, after);
        }
    }

    /**
     * {@link BigDecimal} fields compare by value, not {@code equals} — a model
     * re-emitting {@code 4} as {@code 4.0} on a refine turn is the same number,
     * not drift.
     */
    private void compareNumeric(List<ValidationIssue> changes, String field,
                                BigDecimal before, BigDecimal after) {
        boolean equal = before == null
                ? after == null
                : after != null && before.compareTo(after) == 0;
        if (!equal) {
            addChange(changes, field, before, after);
        }
    }

    private void addChange(List<ValidationIssue> changes, String field, Object before, Object after) {
        changes.add(new ValidationIssue(field, IssueSeverity.ASSUMPTION,
                "Changed during refine: " + before + " -> " + after
                        + ". If your correction did not ask for this, reject it with another correction."));
    }
}
