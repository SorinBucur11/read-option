package app.readoption.scoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("AdpBucket.forReceptionPoints — nearest-bucket mapping for custom scoring")
class AdpBucketTest {

    @ParameterizedTest(name = "{0} points/reception -> {1}")
    @CsvSource({
            "0,    STANDARD",
            "0.25, HALF_PPR",
            "0.5,  HALF_PPR",
            "0.74, HALF_PPR",
            "0.75, PPR",
            "1,    PPR"
    })
    @DisplayName("boundary values land in the specified buckets")
    void boundaries(String receptionPoints, AdpBucket expected) {
        assertThat(AdpBucket.forReceptionPoints(new BigDecimal(receptionPoints)))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("scale does not matter — 0.50 and 0.5 map identically (compareTo semantics)")
    void scaleInsensitive() {
        assertThat(AdpBucket.forReceptionPoints(new BigDecimal("0.50")))
                .isEqualTo(AdpBucket.forReceptionPoints(new BigDecimal("0.5")));
    }

    @ParameterizedTest
    @EnumSource(ScoringFormat.class)
    @DisplayName("agrees with ScoringFormat.adpBucket() on every preset's reception value")
    void agreesWithPresetBuckets(ScoringFormat format) {
        assertThat(AdpBucket.forReceptionPoints(format.toScoringRules().pointsPerReception()))
                .isEqualTo(format.adpBucket());
    }

    @Test
    @DisplayName("null reception points is rejected, not defaulted")
    void nullRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AdpBucket.forReceptionPoints(null));
    }
}
