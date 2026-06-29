package app.readoption.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;

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
}
