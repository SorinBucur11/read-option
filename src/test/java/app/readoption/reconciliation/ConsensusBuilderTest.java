package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsensusBuilder — median + high/low selection")
class ConsensusBuilderTest {

    private static PlayerProjectionRaw raw(String source, String team, BigDecimal recYd, BigDecimal recTd) {
        return PlayerProjectionRaw.builder()
                .playerId("P1").year(2026).source(source).team(team).gamesPlayed(17)
                .receivingYards(recYd)
                .receivingTd(recTd)
                .build();
    }

    private static ScoredSource scored(PlayerProjectionRaw line, double points) {
        return new ScoredSource(line, BigDecimal.valueOf(points));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @DisplayName("two-source median is the per-stat midpoint")
    void twoSourceMidpoint() {
        ProjectionStatLine median = ConsensusBuilder.median(List.of(
                scored(raw("espn", "KC", bd("1000"), bd("8")), 200),
                scored(raw("rotowire", "KC", bd("1200"), bd("10")), 240)));

        assertThat(median.getReceivingYards()).isEqualByComparingTo("1100.00");   // (1000+1200)/2
        assertThat(median.getReceivingTd()).isEqualByComparingTo("9.00");          // (8+10)/2
    }

    @Test
    @DisplayName("odd source count takes the central value")
    void oddCountCentralValue() {
        ProjectionStatLine median = ConsensusBuilder.median(List.of(
                scored(raw("a", "KC", bd("900"), bd("5")), 150),
                scored(raw("b", "KC", bd("1100"), bd("7")), 180),
                scored(raw("c", "KC", bd("1500"), bd("12")), 240)));

        assertThat(median.getReceivingYards()).isEqualByComparingTo("1100.00");   // middle of 900,1100,1500
        assertThat(median.getReceivingTd()).isEqualByComparingTo("7.00");
    }

    @Test
    @DisplayName("a null stat from one source is excluded, not counted as zero")
    void nullStatExcluded() {
        ProjectionStatLine median = ConsensusBuilder.median(List.of(
                scored(raw("a", "KC", bd("1000"), null), 150),
                scored(raw("b", "KC", bd("1200"), bd("10")), 180)));

        assertThat(median.getReceivingYards()).isEqualByComparingTo("1100.00");
        assertThat(median.getReceivingTd()).isEqualByComparingTo("10.00");   // only b had a value
    }

    @Test
    @DisplayName("all sources null for a stat yields null")
    void allNullStatIsNull() {
        ProjectionStatLine median = ConsensusBuilder.median(List.of(
                scored(raw("a", "KC", bd("1000"), null), 150),
                scored(raw("b", "KC", bd("1200"), null), 180)));

        assertThat(median.getReceivingTd()).isNull();
    }

    @Test
    @DisplayName("highest/lowest select the source line by measuring-stick points")
    void highLowSelection() {
        ScoredSource low = scored(raw("espn", "KC", bd("900"), bd("5")), 150);
        ScoredSource high = scored(raw("rotowire", "KC", bd("1500"), bd("12")), 260);
        List<ScoredSource> sources = List.of(low, high);

        assertThat(ConsensusBuilder.highest(sources).line().getSource()).isEqualTo("rotowire");
        assertThat(ConsensusBuilder.lowest(sources).line().getSource()).isEqualTo("espn");
    }
}
