package app.readoption.agent;

import app.readoption.customization.DraftTactics;
import app.readoption.customization.PositionalStrategy;
import app.readoption.customization.RiskPosture;
import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

@DisplayName("AgentPromptBuilder — template + snapshot league facts + tactics")
class AgentPromptBuilderTest {

    private static final String TEMPLATE = "You are a draft advisor. Never invent numbers.";

    private AgentPromptBuilder builder() {
        return new AgentPromptBuilder(
                new ByteArrayResource(TEMPLATE.getBytes(StandardCharsets.UTF_8)));
    }

    private static ScoringRules halfPprTePremium() {
        return ScoringRules.of(new BigDecimal("0.5"), new BigDecimal("4"),
                ScoringRules.DEFAULT_INTERCEPTION_POINTS, new BigDecimal("0.5"));
    }

    private static LeagueSettings twelveTeam() {
        return new LeagueSettings(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR), 0, 8);
    }

    @Test
    @DisplayName("league summary carries format, TE premium, INT rule, teams and rounds")
    void leagueSummary() {
        String prompt = builder().build(halfPprTePremium(), twelveTeam(), null, 12, 15);

        assertThat(prompt)
                .startsWith(TEMPLATE)
                .contains("Half-PPR (0.5/reception)")
                .contains("TE premium +0.5/reception")
                .contains("4 points per passing TD")
                .contains("-2 per interception")
                .contains("12 teams")
                .contains("(15 rounds)");
    }

    @Test
    @DisplayName("tactics render as standing preferences, freeform notes verbatim")
    void tacticsRendered() {
        DraftTactics tactics = new DraftTactics(
                PositionalStrategy.HERO_RB,
                RiskPosture.UPSIDE,
                Map.of(Position.QB, 10),
                List.of("never draft a Jets player"));

        String prompt = builder().build(halfPprTePremium(), twelveTeam(), tactics, 12, 15);

        assertThat(prompt)
                .contains("HERO_RB")
                .contains("UPSIDE")
                .contains("no QB before round 10")
                .contains("never draft a Jets player");
    }

    @Test
    @DisplayName("null tactics -> an explicit 'none stated', not an invented lean")
    void nullTactics() {
        String prompt = builder().build(halfPprTePremium(), twelveTeam(), null, 12, 15);

        assertThat(prompt).contains("No draft tactics stated.");
    }

    @Test
    @DisplayName("a blank prompt resource fails at construction, not on the first request")
    void blankTemplateFailsFast() {
        assertThatIllegalStateException().isThrownBy(() ->
                new AgentPromptBuilder(new ByteArrayResource(
                        "   ".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    @DisplayName("standard rules with no TE bonus omit the premium clause")
    void noTePremiumClauseForStandard() {
        ScoringRules standard = ScoringRules.of(new BigDecimal("0"), new BigDecimal("6"),
                ScoringRules.DEFAULT_INTERCEPTION_POINTS, ScoringRules.NO_TE_BONUS);

        String prompt = builder().build(standard, twelveTeam(), null, 12, 15);

        assertThat(prompt)
                .contains("Standard scoring (0 per reception)")
                .contains("6 points per passing TD")
                .doesNotContain("TE premium");
    }

    @Test
    @DisplayName("teams and rounds come from the session snapshot, never re-derived from settings")
    void snapshotTeamsAndRoundsWin() {
        // twelveTeam() says 12 teams and its slots sum to 15 — the snapshot disagrees
        // on purpose; the prompt must print the snapshot.
        String prompt = builder().build(halfPprTePremium(), twelveTeam(), null, 14, 16);

        assertThat(prompt)
                .contains("14 teams")
                .contains("(16 rounds)")
                .doesNotContain("12 teams")
                .doesNotContain("(15 rounds)");
    }
}
