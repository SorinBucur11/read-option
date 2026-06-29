package app.readoption.reconciliation;

/**
 * The model's structured output — the BeanOutputConverter target. The model
 * classifies a disagreement (verdict), states how sure it is (confidence, an enum),
 * and explains briefly (rationale). It emits no stat and no point.
 */
public record Verdict(
        ReconciliationVerdict verdict,
        Confidence confidence,
        String rationale
) {
}
