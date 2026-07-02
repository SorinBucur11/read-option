package app.readoption.customization;

import app.readoption.scoring.ReceptionFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The scoring portion of the LLM's spec. Every field is exactly one of three kinds:
 *
 * <ul>
 *   <li><b>PRESET</b> — {@code basePreset}: a closed enum the model selects. It has
 *       <b>no safe default</b>: if the description doesn't state how receptions are
 *       scored, the model emits {@code null} and the validator raises a BLOCKING issue
 *       (this is the deliberate divergence from Phase 2's {@code TRUST_CONSENSUS}
 *       fallback — config has no safe fallback).</li>
 *   <li><b>EXTRACTED</b> — {@code passingTdPoints}, {@code interceptionPoints}: numbers
 *       the model may only copy out of the user's text; {@code null} means "not stated,
 *       use the preset default" (resolved deterministically, never guessed).</li>
 *   <li><b>FLAG</b> — {@code tePremium}: the model states that the rule exists; the
 *       resolver supplies the value from its registry. There is deliberately no field
 *       for the bonus amount.</li>
 * </ul>
 */
public record ScoringSpec(
        @NotNull ReceptionFormat basePreset,
        @Min(3) @Max(8) Integer passingTdPoints,
        @Min(-6) @Max(0) Integer interceptionPoints,
        boolean tePremium) {
}
