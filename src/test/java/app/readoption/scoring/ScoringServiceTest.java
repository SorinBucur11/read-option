package app.readoption.scoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScoringServiceTest {

    private final ScoringService scoringService = new ScoringService();

    @Test
    @DisplayName("QB in Standard 6pt: passing yards + 6pt TDs + rush + turnovers")
    void calculateQbStandard6pt() {
        StatLine qb = testStatLine(4000, 30, 10, 200, 2, 0, 0, 0, 3, 1, 17);

        ScoringResult result = scoringService.calculate(qb, ScoringFormat.STANDARD_6PT);

        // 4000*0.04=160 + 30*6=180 + 10*(-2)=-20 + 200*0.1=20 + 2*6=12
        // + 0 + 0 + 0 + 3*(-2)=-6 + 1*2=2 = 348.00
        assertEquals(new BigDecimal("348.00"), result.totalPoints());
        // 348.00 / 17 = 20.47 (HALF_UP)
        assertEquals(new BigDecimal("20.47"), result.pointsPerGame());
    }

    @Test
    @DisplayName("RB in Standard 6pt: rushing + receiving, zero reception value")
    void calculateRbStandard6pt() {
        StatLine rb = testStatLine(0, 0, 0, 1200, 10, 50, 400, 3, 2, 0, 16);

        ScoringResult result = scoringService.calculate(rb, ScoringFormat.STANDARD_6PT);

        // 1200*0.1=120 + 10*6=60 + 50*0=0 + 400*0.1=40 + 3*6=18 + 2*(-2)=-4 = 234.00
        assertEquals(new BigDecimal("234.00"), result.totalPoints());
        assertEquals(new BigDecimal("14.63"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Same RB in PPR 4pt: receptions add 50 points")
    void calculateRbPpr4pt() {
        StatLine rb = testStatLine(0, 0, 0, 1200, 10, 50, 400, 3, 2, 0, 16);

        ScoringResult result = scoringService.calculate(rb, ScoringFormat.PPR_4PT);

        // Same as Standard but receptions: 50 * 1.0 = 50 extra → 234 + 50 = 284.00
        assertEquals(new BigDecimal("284.00"), result.totalPoints());
    }

    @Test
    @DisplayName("4pt vs 6pt passing TD: 10 TDs = 20 point difference")
    void passingTd4ptVs6pt() {
        StatLine qb = testStatLine(0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 17);

        ScoringResult result4pt = scoringService.calculate(qb, ScoringFormat.STANDARD_4PT);
        ScoringResult result6pt = scoringService.calculate(qb, ScoringFormat.STANDARD_6PT);

        assertEquals(new BigDecimal("40.00"), result4pt.totalPoints());
        assertEquals(new BigDecimal("60.00"), result6pt.totalPoints());
    }

    @Test
    @DisplayName("All null stats produce zero points and zero PPG")
    void calculateAllNulls() {
        StatLine empty = testStatLine(null, null, null, null, null,
                null, null, null, null, null, null);

        ScoringResult result = scoringService.calculate(empty, ScoringFormat.STANDARD_6PT);

        assertEquals(new BigDecimal("0.00"), result.totalPoints());
        assertEquals(new BigDecimal("0.00"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Zero games played: total calculated, PPG is zero (no ArithmeticException)")
    void calculateZeroGamesPlayed() {
        StatLine stats = testStatLine(100, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        ScoringResult result = scoringService.calculate(stats, ScoringFormat.STANDARD_6PT);

        // 100*0.04=4 + 1*6=6 = 10.00
        assertEquals(new BigDecimal("10.00"), result.totalPoints());
        assertEquals(new BigDecimal("0.00"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Half-PPR gives half credit per reception")
    void calculateHalfPpr() {
        StatLine wr = testStatLine(0, 0, 0, 0, 0, 80, 1100, 8, 0, 0, 17);

        ScoringResult standard = scoringService.calculate(wr, ScoringFormat.STANDARD_6PT);
        ScoringResult halfPpr = scoringService.calculate(wr, ScoringFormat.HALF_PPR_6PT);
        ScoringResult ppr = scoringService.calculate(wr, ScoringFormat.PPR_6PT);

        // Base: 1100*0.1=110 + 8*6=48 = 158
        assertEquals(new BigDecimal("158.00"), standard.totalPoints());
        assertEquals(new BigDecimal("198.00"), halfPpr.totalPoints());   // +80*0.5=40
        assertEquals(new BigDecimal("238.00"), ppr.totalPoints());       // +80*1.0=80
    }

    @Test
    @DisplayName("2-point conversions and fumbles both counted")
    void twoPtConversionsAndFumbles() {
        // Player with only 3 two-point conversions and 2 fumbles lost
        StatLine stats = testStatLine(0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 17);

        ScoringResult result = scoringService.calculate(stats, ScoringFormat.STANDARD_6PT);

        // 2*(-2)=-4 + 3*2=6 = 2.00
        assertEquals(new BigDecimal("2.00"), result.totalPoints());
    }

    // --- Test helper ---

    /**
     * Creates a StatLine with specified values. No JPA, no Spring, no database.
     * Parameter order matches the scoring categories:
     * passing → rushing → receiving → turnovers → bonuses → games
     */
    private StatLine testStatLine(Integer passingYards, Integer passingTd, Integer interceptions,
                                  Integer rushingYards, Integer rushingTd,
                                  Integer receptions, Integer receivingYards, Integer receivingTd,
                                  Integer fumblesLost, Integer twoPtConv,
                                  Integer gamesPlayed) {
        return new StatLine() {
            @Override public Integer getPassingYards() { return passingYards; }
            @Override public Integer getPassingTd() { return passingTd; }
            @Override public Integer getInterceptions() { return interceptions; }
            @Override public Integer getRushingYards() { return rushingYards; }
            @Override public Integer getRushingTd() { return rushingTd; }
            @Override public Integer getReceptions() { return receptions; }
            @Override public Integer getReceivingYards() { return receivingYards; }
            @Override public Integer getReceivingTd() { return receivingTd; }
            @Override public Integer getFumblesLost() { return fumblesLost; }
            @Override public Integer getTwoPtConv() { return twoPtConv; }
            @Override public Integer getGamesPlayed() { return gamesPlayed; }
        };
    }
}