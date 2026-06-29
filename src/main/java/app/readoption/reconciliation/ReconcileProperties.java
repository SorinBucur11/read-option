package app.readoption.reconciliation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Reconciliation knobs, validated at the startup boundary so a bad value fails fast
 * rather than mid-batch. None of the three numbers are hardcoded in service code.
 *
 * @param cvThreshold     CV above which a 2+-source player is contested and goes to the
 *                        model. Provisional — calibrate via the dry-run distribution first.
 * @param pointsFloor     measuring-stick points below which a player is skipped as
 *                        non-draftable (also guards the CV mean against → 0).
 * @param measuringStick  the disagreement detector (default PPR; see {@link MeasuringStick}).
 * @param model           the Anthropic model id used to classify contested players.
 * @param systemPrompt    the classifier system prompt. Externalized so it can be tuned
 *                        without recompiling during prompt iteration (restart to apply).
 */
@Validated
@ConfigurationProperties(prefix = "readoption.reconcile")
public record ReconcileProperties(

        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal cvThreshold,

        @NotNull @DecimalMin("0.0") BigDecimal pointsFloor,

        @NotNull MeasuringStick measuringStick,

        @NotBlank String model,

        @NotBlank String systemPrompt
) {
}
