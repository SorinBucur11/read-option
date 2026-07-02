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

        ScoringResult result = calculate(qb, ScoringFormat.STANDARD_6PT, Position.QB);

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

        ScoringResult result = calculate(rb, ScoringFormat.STANDARD_6PT, Position.RB);

        // 1200*0.1=120 + 10*6=60 + 50*0=0 + 400*0.1=40 + 3*6=18 + 2*(-2)=-4 = 234.00
        assertEquals(new BigDecimal("234.00"), result.totalPoints());
        assertEquals(new BigDecimal("14.63"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Same RB in PPR 4pt: receptions add 50 points")
    void calculateRbPpr4pt() {
        StatLine rb = testStatLine(0, 0, 0, 1200, 10, 50, 400, 3, 2, 0, 16);

        ScoringResult result = calculate(rb, ScoringFormat.PPR_4PT, Position.RB);

        // Same as Standard but receptions: 50 * 1.0 = 50 extra → 234 + 50 = 284.00
        assertEquals(new BigDecimal("284.00"), result.totalPoints());
    }

    @Test
    @DisplayName("4pt vs 6pt passing TD: 10 TDs = 20 point difference")
    void passingTd4ptVs6pt() {
        StatLine qb = testStatLine(0, 10, 0, 0, 0, 0, 0, 0, 0, 0, 17);

        ScoringResult result4pt = calculate(qb, ScoringFormat.STANDARD_4PT, Position.QB);
        ScoringResult result6pt = calculate(qb, ScoringFormat.STANDARD_6PT, Position.QB);

        assertEquals(new BigDecimal("40.00"), result4pt.totalPoints());
        assertEquals(new BigDecimal("60.00"), result6pt.totalPoints());
    }

    @Test
    @DisplayName("All null stats produce zero points and zero PPG")
    void calculateAllNulls() {
        StatLine empty = testStatLine(null, null, null, null, null,
                null, null, null, null, null, null);

        ScoringResult result = calculate(empty, ScoringFormat.STANDARD_6PT, Position.WR);

        assertEquals(new BigDecimal("0.00"), result.totalPoints());
        assertEquals(new BigDecimal("0.00"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Zero games played: total calculated, PPG is zero (no ArithmeticException)")
    void calculateZeroGamesPlayed() {
        StatLine stats = testStatLine(100, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        ScoringResult result = calculate(stats, ScoringFormat.STANDARD_6PT, Position.QB);

        // 100*0.04=4 + 1*6=6 = 10.00
        assertEquals(new BigDecimal("10.00"), result.totalPoints());
        assertEquals(new BigDecimal("0.00"), result.pointsPerGame());
    }

    @Test
    @DisplayName("Half-PPR gives half credit per reception")
    void calculateHalfPpr() {
        StatLine wr = testStatLine(0, 0, 0, 0, 0, 80, 1100, 8, 0, 0, 17);

        ScoringResult standard = calculate(wr, ScoringFormat.STANDARD_6PT, Position.WR);
        ScoringResult halfPpr = calculate(wr, ScoringFormat.HALF_PPR_6PT, Position.WR);
        ScoringResult ppr = calculate(wr, ScoringFormat.PPR_6PT, Position.WR);

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

        ScoringResult result = calculate(stats, ScoringFormat.STANDARD_6PT, Position.RB);

        // 2*(-2)=-4 + 3*2=6 = 2.00
        assertEquals(new BigDecimal("2.00"), result.totalPoints());
    }

    @Test
    @DisplayName("No preset carries a TE premium: a TE scores identically to a WR")
    void presetsHaveNoTePremium() {
        StatLine line = testStatLine(0, 0, 0, 0, 0, 80, 1100, 8, 0, 0, 17);

        ScoringResult asTe = calculate(line, ScoringFormat.PPR_6PT, Position.TE);
        ScoringResult asWr = calculate(line, ScoringFormat.PPR_6PT, Position.WR);

        // 1100*0.1=110 + 8*6=48 + 80*1.0=80 = 238.00, regardless of position.
        assertEquals(new BigDecimal("238.00"), asTe.totalPoints());
        assertEquals(asWr.totalPoints(), asTe.totalPoints());
    }

    @Test
    @DisplayName("TE premium applies to TEs only when the rules carry a bonus")
    void teReceptionBonusAppliesToTeOnly() {
        StatLine line = testStatLine(0, 0, 0, 0, 0, 80, 1100, 8, 0, 0, 17);
        // PPR base (1.0/reception) plus a 0.5 TE premium.
        ScoringRules tePremium = ScoringRules.of(
                new BigDecimal("1.0"), new BigDecimal("6"),
                ScoringRules.DEFAULT_INTERCEPTION_POINTS, new BigDecimal("0.5"));

        ScoringResult te = scoringService.calculate(line, tePremium, Position.TE);
        ScoringResult wr = scoringService.calculate(line, tePremium, Position.WR);

        // WR: 110 + 48 + 80*1.0 = 238.00; TE adds 80*0.5 = 40 → 278.00.
        assertEquals(new BigDecimal("238.00"), wr.totalPoints());
        assertEquals(new BigDecimal("278.00"), te.totalPoints());
    }

    // --- Test helpers ---

    /** Score a stat line through a named preset, the path the leaderboard loop uses. */
    private ScoringResult calculate(StatLine stats, ScoringFormat format, Position position) {
        return scoringService.calculate(stats, format.toScoringRules(), position);
    }

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
