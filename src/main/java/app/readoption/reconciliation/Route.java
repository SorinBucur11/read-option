package app.readoption.reconciliation;

/**
 * How a player's mart line was decided — recorded on the audit row, kept honest about
 * whether the model was consulted at all.
 */
public enum Route {
    /** CV under threshold: deterministic median, no model call. */
    CONSENSUS,
    /** Only one source had a row: no dispersion, no model call. */
    SINGLE_SOURCE,
    /** CV over threshold: the model classified the disagreement. */
    LLM,
    /** The model call failed: fell back to the median. */
    LLM_FALLBACK
}
