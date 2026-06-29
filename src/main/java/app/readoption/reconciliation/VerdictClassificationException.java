package app.readoption.reconciliation;

/**
 * Raised when the model call or its structured-output parse fails. The engine catches
 * this per player and falls back to the median (route LLM_FALLBACK) — one bad call
 * never aborts the batch.
 */
public class VerdictClassificationException extends RuntimeException {

    public VerdictClassificationException(String message, Throwable cause) {
        super(message, cause);
    }

    public VerdictClassificationException(String message) {
        super(message);
    }
}
