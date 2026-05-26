package app.readoption.scoring;

import java.util.Set;

/**
 * Fantasy-relevant NFL positions.
 *
 * The Player entity keeps position as a String for flexible ingestion
 * from external APIs (Sleeper returns positions like FB, DB, DL that
 * we filter out). This enum is for application logic where we control
 * the values: league settings, sync filtering, scoring, and draft strategy.
 */
public enum Position {

    QB("Quarterback"),
    RB("Running Back"),
    WR("Wide Receiver"),
    TE("Tight End"),
    K("Kicker"),
    DEF("Defense/Special Teams");

    private final String displayName;

    Position(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Fantasy-relevant positions for data sync.
     * K and DEF are synced but excluded from scoring MVP.
     */
    public static final Set<String> FANTASY_POSITION_NAMES =
            Set.of("QB", "RB", "WR", "TE", "K", "DEF");
}