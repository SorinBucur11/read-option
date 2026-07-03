package app.readoption.customization;

import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringRules;
import app.readoption.scoring.ScoringService;
import app.readoption.scoring.StatLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit — the resolver is the deterministic half of the spec → resolver → domain
 * split, so every test here runs with zero model involvement. The registry numbers
 * ({@code TE_PREMIUM_BONUS}, the preset defaults) are asserted by value: they exist
 * nowhere else, in particular not in the parse targets.
 */
@DisplayName("LeagueRulesResolver — preset application, delta overlay, registry, pass-through")
class LeagueRulesResolverTest {

    private final LeagueRulesResolver resolver = new LeagueRulesResolver();

    private static RosterSpec roster() {
        return new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
    }

    private static LeagueRulesSpec spec(ScoringSpec scoring, PlayoffSpec playoff) {
        return new LeagueRulesSpec(scoring, roster(), playoff);
    }

    @Test
    @DisplayName("basePreset drives the reception axis: STANDARD 0 / HALF_PPR 0.5 / PPR 1.0")
    void presetDrivesReceptionAxis() {
        for (ReceptionFormat preset : ReceptionFormat.values()) {
            LeagueRules resolved = resolver.resolve(
                    spec(new ScoringSpec(preset, null, null, false), null));

            assertThat(resolved.scoring().pointsPerReception())
                    .isEqualByComparingTo(preset.pointsPerReception());
        }
    }

    @Test
    @DisplayName("null extracted deltas fall back to preset defaults: 4 per passing TD, -2 per INT")
    void nullDeltasUsePresetDefaults() {
        LeagueRules resolved = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.PPR, null, null, false), null));

        assertThat(resolved.scoring().passingTdPoints()).isEqualByComparingTo("4");
        assertThat(resolved.scoring().interceptionPoints()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("stated deltas overlay the defaults exactly, fractions included")
    void statedDeltasOverlay() {
        LeagueRules resolved = resolver.resolve(spec(new ScoringSpec(
                ReceptionFormat.PPR, new BigDecimal("6"), new BigDecimal("-0.5"), false), null));

        assertThat(resolved.scoring().passingTdPoints()).isEqualByComparingTo("6");
        assertThat(resolved.scoring().interceptionPoints()).isEqualByComparingTo("-0.5");
    }

    @Test
    @DisplayName("tePremium flag resolves to the registry value; no flag resolves to zero")
    void tePremiumFlagResolvesFromRegistry() {
        LeagueRules premium = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.HALF_PPR, null, null, true), null));
        LeagueRules plain = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.HALF_PPR, null, null, false), null));

        assertThat(premium.scoring().teReceptionBonus()).isEqualByComparingTo("0.5");
        assertThat(plain.scoring().teReceptionBonus()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("fixed axes come from the standard constants — the resolver never invents them")
    void fixedAxesAreStandardConstants() {
        ScoringRules scoring = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.STANDARD, null, null, false), null)).scoring();

        assertThat(scoring.passingYardPoints()).isEqualByComparingTo(ScoringRules.STANDARD_PASS_YARD_POINTS);
        assertThat(scoring.rushingYardPoints()).isEqualByComparingTo(ScoringRules.STANDARD_RUSH_YARD_POINTS);
        assertThat(scoring.receivingYardPoints()).isEqualByComparingTo(ScoringRules.STANDARD_REC_YARD_POINTS);
        assertThat(scoring.rushingTdPoints()).isEqualByComparingTo(ScoringRules.STANDARD_RUSH_TD_POINTS);
        assertThat(scoring.receivingTdPoints()).isEqualByComparingTo(ScoringRules.STANDARD_REC_TD_POINTS);
        assertThat(scoring.fumbleLostPoints()).isEqualByComparingTo(ScoringRules.STANDARD_FUMBLE_LOST_POINTS);
        assertThat(scoring.twoPtConvPoints()).isEqualByComparingTo(ScoringRules.STANDARD_TWO_PT_CONV_POINTS);
    }

    @Test
    @DisplayName("roster is pure extraction — every field maps straight through")
    void rosterMapsStraightThrough() {
        RosterSpec roster = new RosterSpec(10, 2, 3, 4, 2, 2,
                Set.of(Position.RB, Position.WR), 1, 8);
        LeagueRules resolved = resolver.resolve(new LeagueRulesSpec(
                new ScoringSpec(ReceptionFormat.STANDARD, null, null, false), roster, null));

        var settings = resolved.roster();
        assertThat(settings.teams()).isEqualTo(10);
        assertThat(settings.qbSlots()).isEqualTo(2);
        assertThat(settings.rbSlots()).isEqualTo(3);
        assertThat(settings.wrSlots()).isEqualTo(4);
        assertThat(settings.teSlots()).isEqualTo(2);
        assertThat(settings.flexSlots()).isEqualTo(2);
        assertThat(settings.flexEligible()).containsExactlyInAnyOrder(Position.RB, Position.WR);
        assertThat(settings.superflexSlots()).isEqualTo(1);
        assertThat(settings.benchSlots()).isEqualTo(8);
    }

    @Test
    @DisplayName("resolved TE premium reaches the engine and applies to TEs only, not WR/RB")
    void resolvedTePremiumAppliesToTeOnly() {
        // The full spec → resolver → engine chain: a tePremium flag must surface as
        // extra points for a TE stat line and leave a WR line untouched.
        ScoringRules rules = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.PPR, null, null, true), null)).scoring();
        ScoringService engine = new ScoringService();
        StatLine line = receiverLine();

        ScoringResult te = engine.calculate(line, rules, Position.TE);
        ScoringResult wr = engine.calculate(line, rules, Position.WR);
        ScoringResult rb = engine.calculate(line, rules, Position.RB);

        // 80 rec * 0.5 bonus = 40 extra for the TE only.
        assertThat(wr.totalPoints()).isEqualByComparingTo(rb.totalPoints());
        assertThat(te.totalPoints().subtract(wr.totalPoints())).isEqualByComparingTo("40.00");
    }

    /** 80 receptions, 1100 yards, 8 TDs, 17 games — a receiving-only stat line. */
    private static StatLine receiverLine() {
        return new StatLine() {
            @Override public Integer getPassingYards() { return null; }
            @Override public Integer getPassingTd() { return null; }
            @Override public Integer getInterceptions() { return null; }
            @Override public Integer getRushingYards() { return null; }
            @Override public Integer getRushingTd() { return null; }
            @Override public Integer getReceptions() { return 80; }
            @Override public Integer getReceivingYards() { return 1100; }
            @Override public Integer getReceivingTd() { return 8; }
            @Override public Integer getFumblesLost() { return null; }
            @Override public Integer getTwoPtConv() { return null; }
            @Override public Integer getGamesPlayed() { return 17; }
        };
    }

    @Test
    @DisplayName("playoff: null stays null; stated maps straight through")
    void playoffPassThrough() {
        LeagueRules withoutPlayoff = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.PPR, null, null, false), null));
        LeagueRules withPlayoff = resolver.resolve(
                spec(new ScoringSpec(ReceptionFormat.PPR, null, null, false),
                        new PlayoffSpec(6, 15, 17)));

        assertThat(withoutPlayoff.playoff()).isNull();
        assertThat(withPlayoff.playoff())
                .isEqualTo(new PlayoffFormat(6, 15, 17));
    }
}
