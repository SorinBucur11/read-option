package app.readoption.customization;

import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import app.readoption.scoring.ScoringRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("LeagueConfig — confirmed row to engine domain objects (pure mapping, no invented defaults)")
class LeagueConfigMappingTest {

    private static LeagueConfig.LeagueConfigBuilder confirmedConfig() {
        return LeagueConfig.builder()
                .id(42L)
                .receptionFormat(ReceptionFormat.HALF_PPR)
                .passingTdPoints(new BigDecimal("6"))
                .interceptionPoints(new BigDecimal("-1"))
                .teReceptionBonus(new BigDecimal("0.5"))
                .teamCount(12)
                .qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(1)
                .flexEligible(Set.of(Position.RB, Position.WR, Position.TE))
                .superflexSlots(1).benchSlots(6);
    }

    @Test
    @DisplayName("toScoringRules maps the four variable axes and keeps the fixed standard rules")
    void toScoringRulesMapsAxes() {
        ScoringRules rules = confirmedConfig().build().toScoringRules();

        assertThat(rules.pointsPerReception()).isEqualByComparingTo("0.5");
        assertThat(rules.passingTdPoints()).isEqualByComparingTo("6");
        assertThat(rules.interceptionPoints()).isEqualByComparingTo("-1");
        assertThat(rules.teReceptionBonus()).isEqualByComparingTo("0.5");
        // fixed rules come from the shared constants, not the row
        assertThat(rules.passingYardPoints()).isEqualByComparingTo("0.04");
        assertThat(rules.rushingTdPoints()).isEqualByComparingTo("6");
        assertThat(rules.fumbleLostPoints()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("toScoringRules throws on a null scoring column — impossible post-resolution, never defaulted")
    void toScoringRulesThrowsOnNull() {
        LeagueConfig missingReception = confirmedConfig().receptionFormat(null).build();
        LeagueConfig missingTdPoints = confirmedConfig().passingTdPoints(null).build();

        assertThatIllegalStateException().isThrownBy(missingReception::toScoringRules);
        assertThatIllegalStateException().isThrownBy(missingTdPoints::toScoringRules);
    }

    @Test
    @DisplayName("toLeagueSettings maps the full roster shape including superflex and bench")
    void toLeagueSettingsMapsRoster() {
        LeagueSettings settings = confirmedConfig().build().toLeagueSettings();

        assertThat(settings.teams()).isEqualTo(12);
        assertThat(settings.qbSlots()).isEqualTo(1);
        assertThat(settings.rbSlots()).isEqualTo(2);
        assertThat(settings.wrSlots()).isEqualTo(2);
        assertThat(settings.teSlots()).isEqualTo(1);
        assertThat(settings.flexSlots()).isEqualTo(1);
        assertThat(settings.flexEligible()).containsExactlyInAnyOrder(
                Position.RB, Position.WR, Position.TE);
        assertThat(settings.superflexSlots()).isEqualTo(1);
        assertThat(settings.benchSlots()).isEqualTo(6);
    }

    @Test
    @DisplayName("toLeagueSettings throws on a null flex-eligible set")
    void toLeagueSettingsThrowsOnNullFlexEligible() {
        LeagueConfig broken = confirmedConfig().flexEligible(null).build();

        assertThatIllegalStateException().isThrownBy(broken::toLeagueSettings);
    }
}
