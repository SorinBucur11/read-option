package app.readoption.reconciliation;

import app.readoption.scoring.ScoringFormat;

/**
 * The scoring lens used to <i>measure</i> source disagreement — a detector, not a
 * played format and not an output. PPR points = Standard points + receptions, so PPR
 * sees every disagreement Standard sees plus reception (target-share) disagreements;
 * Standard alone has a reception-shaped blind spot. PPR is the configured default;
 * STANDARD exists so the §4 dual dry-run can confirm the contested sets differ only
 * on reception-contested players before PPR is locked.
 */
public enum MeasuringStick {

    PPR(ScoringFormat.PPR_4PT),
    STANDARD(ScoringFormat.STANDARD_4PT);

    private final ScoringFormat scoringFormat;

    MeasuringStick(ScoringFormat scoringFormat) {
        this.scoringFormat = scoringFormat;
    }

    public ScoringFormat scoringFormat() {
        return scoringFormat;
    }
}
