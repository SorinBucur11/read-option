package app.readoption.customization;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * League-customization knobs, validated at the startup boundary so a bad value fails
 * fast rather than on the first parse request.
 *
 * @param model          the Anthropic model id used to parse league descriptions.
 * @param systemPrompt   the parser system prompt. Externalized so it can be tuned
 *                       without recompiling during prompt iteration (restart to apply).
 * @param maxRefineTurns hard cap on repair-loop turns. The loop must terminate: past
 *                       the cap the API returns the partial object plus unresolved
 *                       issues and asks the user to state the missing fields explicitly.
 */
@Validated
@ConfigurationProperties(prefix = "readoption.customization")
public record CustomizationProperties(

        @NotBlank String model,

        @NotBlank String systemPrompt,

        @Min(1) @Max(20) int maxRefineTurns
) {
}
