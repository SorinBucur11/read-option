package app.readoption.espn;

/**
 * ESPN numeric stat ids → our canonical scoring fields.
 *
 * Verification method: cross-referenced ESPN season-actual totals (statSourceId 0,
 * scoringPeriodId 0) against our already-validated rotowire stats as ground truth,
 * intersecting candidate keys across multiple players to eliminate coincidences.
 *
 *   CONFIRMED:
 *     rush/rec ids   — Gibbs (RB) raw stats + hand-scored reconstruction
 *     passing ids    — Mahomes (QB), incl. interceptions=20 (known 3-INT game)
 *     fumbles_lost=72 — intersection across Fields/Nix/Dak (known 3/2/2); the
 *                       decoy key 73 was turnovers (fumbles+INTs), eliminated.
 *
 *   UNMAPPED — two_pt_conv:
 *     Our canonical two_pt_conv is a combined total (pass_2pt + rush_2pt). ESPN
 *     splits two-point conversions, and neither a single key nor any key-pair
 *     matched the known totals (5/4/3) across all three probes — only per-player
 *     coincidences, zero three-way survivors. Left null deliberately: +2 pts,
 *     rare, and a wrong mapping would silently corrupt scores. Revisit only if a
 *     reliable ESPN two-pt id surfaces.
 */
public final class EspnStatId {

    private EspnStatId() {}

    // --- rushing / receiving (confirmed) ---
    public static final String RUSHING_YARDS   = "24";
    public static final String RUSHING_TD      = "25";
    public static final String RECEPTIONS      = "53";
    public static final String RECEIVING_YARDS = "42";
    public static final String RECEIVING_TD    = "43";

    // --- passing (confirmed against QB data) ---
    public static final String PASSING_YARDS   = "3";
    public static final String PASSING_TD      = "4";
    public static final String INTERCEPTIONS   = "20";

    // --- turnovers (confirmed by triangulation) ---
    public static final String FUMBLES_LOST    = "72";

    // two_pt_conv: intentionally unmapped — see class javadoc.
}