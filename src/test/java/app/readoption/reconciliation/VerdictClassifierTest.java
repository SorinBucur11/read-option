package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("VerdictClassifier — structured-output parse seam")
class VerdictClassifierTest {

    private VerdictClassifier classifier;

    @BeforeEach
    void setUp() {
        // The transport is never exercised here — only the parse seam. Stub the builder
        // so construction succeeds without a live model.
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        ReconcileProperties props = new ReconcileProperties(
                new BigDecimal("0.12"), new BigDecimal("20.0"), MeasuringStick.PPR, "claude-sonnet-4-6",
                "test system prompt");
        classifier = new VerdictClassifier(builder, props);
    }

    @Test
    @DisplayName("well-formed JSON parses into a Verdict")
    void parsesValidVerdict() {
        Verdict verdict = classifier.parse(
                "{\"verdict\":\"FAVOR_HIGH_SOURCE\",\"confidence\":\"HIGH\",\"rationale\":\"volume-driven\"}");

        assertThat(verdict.verdict()).isEqualTo(ReconciliationVerdict.FAVOR_HIGH_SOURCE);
        assertThat(verdict.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(verdict.rationale()).isEqualTo("volume-driven");
    }

    @Test
    @DisplayName("an out-of-enum verdict surfaces as the fallback signal")
    void outOfEnumThrows() {
        assertThatThrownBy(() -> classifier.parse(
                "{\"verdict\":\"MAYBE\",\"confidence\":\"HIGH\",\"rationale\":\"x\"}"))
                .isInstanceOf(VerdictClassificationException.class);
    }

    @Test
    @DisplayName("malformed (non-JSON) output surfaces as the fallback signal")
    void malformedThrows() {
        assertThatThrownBy(() -> classifier.parse("I think the high source is right."))
                .isInstanceOf(VerdictClassificationException.class);
    }

    @Test
    @DisplayName("missing verdict field surfaces as the fallback signal")
    void missingVerdictThrows() {
        assertThatThrownBy(() -> classifier.parse(
                "{\"confidence\":\"HIGH\",\"rationale\":\"x\"}"))
                .isInstanceOf(VerdictClassificationException.class);
    }

    @Test
    @DisplayName("the actuals block renders most-recent-first, with games and non-zero stats only")
    void actualsBlockRendersPopulatedHistory() {
        ContestedPlayer player = contested(List.of(
                new SeasonActuals(2025, 16, null, null, 280, 2, 18, 140, 1),
                new SeasonActuals(2024, 17, null, null, 520, 4, 24, 190, 0)));

        String prompt = classifier.buildUserPrompt(player);

        assertThat(prompt).contains("Recent actual production:");
        assertThat(prompt).contains("- 2025: 16 g  rushYd=280  rushTd=2  rec=18  recYd=140  recTd=1");
        // 2024's recTd is zero → skipped, same as the source breakdown skip rule.
        assertThat(prompt).contains("- 2024: 17 g  rushYd=520  rushTd=4  rec=24  recYd=190");
        assertThat(prompt.indexOf("- 2025:")).isLessThan(prompt.indexOf("- 2024:"));
    }

    @Test
    @DisplayName("empty history renders the 'none on record' line, not an empty block")
    void actualsBlockRendersEmptyHistory() {
        ContestedPlayer player = contested(List.of());

        String prompt = classifier.buildUserPrompt(player);

        assertThat(prompt).contains(
                "Recent actual production: none on record (rookie or no prior NFL stats).");
    }

    @Test
    @DisplayName("the actuals block sits before the classify line")
    void actualsBlockPrecedesClassifyLine() {
        ContestedPlayer player = contested(List.of(
                new SeasonActuals(2025, 16, null, null, 280, 2, 18, 140, 1)));

        String prompt = classifier.buildUserPrompt(player);

        assertThat(prompt.indexOf("Recent actual production"))
                .isLessThan(prompt.indexOf("Where does the gap live"));
    }

    private static ContestedPlayer contested(List<SeasonActuals> priorActuals) {
        PlayerProjectionRaw high = PlayerProjectionRaw.builder()
                .playerId("p").year(2026).source("espn")
                .rushingYards(new BigDecimal("700")).rushingTd(new BigDecimal("6"))
                .build();
        PlayerProjectionRaw low = PlayerProjectionRaw.builder()
                .playerId("p").year(2026).source("rotowire")
                .rushingYards(new BigDecimal("300")).rushingTd(new BigDecimal("2"))
                .build();
        List<ContestedPlayer.Source> sources = List.of(
                new ContestedPlayer.Source("espn", new BigDecimal("180.0"), high),
                new ContestedPlayer.Source("rotowire", new BigDecimal("90.0"), low));
        return new ContestedPlayer("Samaje Perine", "RB", "KC", "PPR",
                sources, "espn", new BigDecimal("180.0"), "rotowire", new BigDecimal("90.0"),
                priorActuals);
    }
}
