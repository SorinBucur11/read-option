package app.readoption.reconciliation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("DispersionCalculator — pure CV math")
class DispersionCalculatorTest {

    private static List<BigDecimal> points(double... values) {
        return java.util.Arrays.stream(values).mapToObj(BigDecimal::valueOf).toList();
    }

    @Test
    @DisplayName("n=2 reduces to |a-b|/(a+b)")
    void twoSourceReduction() {
        // |10-30|/(10+30) = 20/40 = 0.5
        Double cv = DispersionCalculator.coefficientOfVariation(points(10, 30));
        assertThat(cv).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("uses the population denominator (÷n), not the sample (÷n-1)")
    void populationDenominator() {
        // [10,30]: population stddev = 10 (sample would be ~14.14)
        assertThat(DispersionCalculator.populationStdDev(points(10, 30)))
                .isCloseTo(10.0, within(1e-9));
        assertThat(DispersionCalculator.mean(points(10, 30))).isCloseTo(20.0, within(1e-9));
    }

    @Test
    @DisplayName("three sources: CV = population stddev / mean")
    void threeSources() {
        // [10,20,30]: mean=20, popVar=(100+0+100)/3, stddev≈8.16497, cv≈0.40825
        Double cv = DispersionCalculator.coefficientOfVariation(points(10, 20, 30));
        assertThat(cv).isCloseTo(0.40825, within(1e-5));
    }

    @Test
    @DisplayName("mean of zero returns null (divide-by-zero guard, no exception)")
    void meanZeroGuard() {
        assertThat(DispersionCalculator.coefficientOfVariation(points(0, 0))).isNull();
    }

    @Test
    @DisplayName("fewer than two points has no dispersion to measure")
    void singleOrEmpty() {
        assertThat(DispersionCalculator.coefficientOfVariation(points(42))).isNull();
        assertThat(DispersionCalculator.coefficientOfVariation(List.of())).isNull();
        assertThat(DispersionCalculator.coefficientOfVariation(null)).isNull();
    }

    @Test
    @DisplayName("identical sources have zero dispersion")
    void identicalSources() {
        assertThat(DispersionCalculator.coefficientOfVariation(points(100, 100)))
                .isCloseTo(0.0, within(1e-9));
    }
}
