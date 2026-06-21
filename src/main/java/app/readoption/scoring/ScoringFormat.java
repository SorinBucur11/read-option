package app.readoption.scoring;

/**
 * Fantasy football scoring formats.
 *
 * Only two rules vary between formats:
 * - Points per reception: 0 (Standard), 0.5 (Half-PPR), 1.0 (PPR)
 * - Points per passing TD: 4 (traditional) or 6
 *
 * All other scoring rules (rushing yards, receiving TDs, etc.)
 * are constant across formats — those live in ScoringService.
 */
public enum ScoringFormat {

    STANDARD_4PT("Standard", 0.0, 4),
    STANDARD_6PT("Standard (6pt Pass TD)", 0.0, 6),
    HALF_PPR_4PT("Half PPR", 0.5, 4),
    HALF_PPR_6PT("Half PPR (6pt Pass TD", 0.5, 6),
    PPR_4PT("PPR", 1.0, 4),
    PPR_6PT("PPR (6pt Pass TD)", 1.0, 6);

    private final String displayName;
    private final double pointsPerReception;
    private final int passingTdPoints;

    ScoringFormat(String displayName, double pointsPerReception, int passingTdPoints) {
        this.displayName = displayName;
        this.pointsPerReception = pointsPerReception;
        this.passingTdPoints = passingTdPoints;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getPointsPerReception() {
        return pointsPerReception;
    }

    public int getPassingTdPoints() {
        return passingTdPoints;
    }

    public AdpBucket adpBucket() {
        return switch (this) {
            case STANDARD_4PT, STANDARD_6PT -> AdpBucket.STANDARD;
            case HALF_PPR_4PT, HALF_PPR_6PT -> AdpBucket.HALF_PPR;
            case PPR_4PT, PPR_6PT -> AdpBucket.PPR;
        };
    }
}
