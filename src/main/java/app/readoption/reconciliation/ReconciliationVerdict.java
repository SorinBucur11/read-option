package app.readoption.reconciliation;

/**
 * How the engine should resolve a contested player. The model returns one of these;
 * the deterministic engine applies it as a selection rule over stat lines already in
 * hand. The model never produces a number — only this classification.
 */
public enum ReconciliationVerdict {
    /** Engine writes the per-stat median. */
    TRUST_CONSENSUS,
    /** Engine writes the highest-points source's line. */
    FAVOR_HIGH_SOURCE,
    /** Engine writes the lowest-points source's line. */
    FAVOR_LOW_SOURCE,
    /** Engine writes the median; the audit flags the player for review. */
    FLAG_UNCERTAIN
}
