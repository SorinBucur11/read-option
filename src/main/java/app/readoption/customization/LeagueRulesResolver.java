package app.readoption.customization;

import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.ScoringRules;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deterministic mapper from the LLM's {@link LeagueRulesSpec} to the engine's
 * {@link LeagueRules}. <b>The flag→number registry lives here and only here</b>: the
 * model can set the {@code tePremium} flag, but the bonus value it resolves to is a
 * constant in this class — the model has no field to write it into.
 *
 * <p>No LLM involvement anywhere in this class; it must stay unit-testable with zero
 * model calls. All numbers ride the {@link BigDecimal} String-constructor path.
 */
@Component
public class LeagueRulesResolver {

    /**
     * Registry: what the "TE premium" flag is worth — extra points per reception for
     * TEs, on top of the league's base reception value. This number exists nowhere
     * else; in particular, it has no representation in the parse targets.
     */
    static final BigDecimal TE_PREMIUM_BONUS = new BigDecimal("0.5");

    /**
     * Registry: preset default for passing TDs when the user didn't state a value.
     * The reception preset carries no TD axis, so the conventional 4 applies.
     */
    static final BigDecimal DEFAULT_PASSING_TD_POINTS = new BigDecimal("4");

    public LeagueRules resolve(LeagueRulesSpec spec) {
        ScoringSpec scoring = spec.scoring();

        // Overlay extracted deltas where non-null; else the preset/registry default.
        // Integer → BigDecimal goes through toString, never the double constructor.
        BigDecimal passingTd = scoring.passingTdPoints() != null
                ? new BigDecimal(scoring.passingTdPoints().toString())
                : DEFAULT_PASSING_TD_POINTS;
        BigDecimal interception = scoring.interceptionPoints() != null
                ? new BigDecimal(scoring.interceptionPoints().toString())
                : ScoringRules.DEFAULT_INTERCEPTION_POINTS;
        BigDecimal teBonus = scoring.tePremium()
                ? TE_PREMIUM_BONUS
                : ScoringRules.NO_TE_BONUS;

        ScoringRules rules = ScoringRules.of(
                scoring.basePreset().pointsPerReception(),
                passingTd,
                interception,
                teBonus);

        return new LeagueRules(rules, toSettings(spec.roster()), toPlayoff(spec.playoff()));
    }

    /** Roster is pure extraction — mapped straight through, no registry. */
    private LeagueSettings toSettings(RosterSpec roster) {
        return new LeagueSettings(
                roster.teamCount(),
                roster.qbSlots(),
                roster.rbSlots(),
                roster.wrSlots(),
                roster.teSlots(),
                roster.flexSlots(),
                roster.flexEligible(),
                roster.superflexSlots(),
                roster.benchSlots());
    }

    private PlayoffFormat toPlayoff(PlayoffSpec playoff) {
        if (playoff == null) {
            return null;
        }
        return new PlayoffFormat(
                playoff.playoffTeams(),
                playoff.playoffStartWeek(),
                playoff.playoffEndWeek());
    }
}
