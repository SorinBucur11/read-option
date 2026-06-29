package app.readoption.reconciliation;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pure dispersion math over a set of measuring-stick point totals. No Spring, no DB
 * — fully unit-testable. The reconciliation engine uses the coefficient of variation
 * (CV = population std dev / mean) as a scale-free measure of how much the sources
 * disagree, so a 5-point spread on a 30-point player counts the same as a 50-point
 * spread on a 300-point player.
 */
public final class DispersionCalculator {

    private DispersionCalculator() {
    }

    /**
     * Coefficient of variation of the point totals, or {@code null} when it is
     * undefined: fewer than two points (no spread to measure) or a mean of zero
     * (the divide-by-zero guard — the points floor upstream already keeps the mean
     * well clear of zero, but the guard is honest about the math).
     *
     * <p>Uses the <b>population</b> standard deviation (÷n): we hold every source,
     * not a sample of them. With n=2 this reduces algebraically to |a-b|/(a+b).
     */
    public static Double coefficientOfVariation(List<BigDecimal> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        double mean = mean(points);
        if (mean == 0.0) {
            return null;
        }
        return populationStdDev(points) / mean;
    }

    public static double mean(List<BigDecimal> points) {
        double sum = 0.0;
        for (BigDecimal p : points) {
            sum += p.doubleValue();
        }
        return sum / points.size();
    }

    /** Population standard deviation (÷n, not ÷(n-1)): all sources are in hand. */
    public static double populationStdDev(List<BigDecimal> points) {
        double mean = mean(points);
        double sumSq = 0.0;
        for (BigDecimal p : points) {
            double d = p.doubleValue() - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / points.size());
    }
}
