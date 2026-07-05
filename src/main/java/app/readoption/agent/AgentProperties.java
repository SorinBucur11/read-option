package app.readoption.agent;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Draft-agent knobs, validated at the startup boundary so a bad value fails fast
 * rather than on the first advice request. The agent system prompt is not here —
 * it lives in {@code classpath:prompts/draft-agent-system.txt} (same rationale as
 * the league-parser prompt); {@link AgentPromptBuilder} loads it and fails fast.
 *
 * @param model         the Anthropic model id used for the advice loop.
 * @param maxIterations hard cap on tool round trips per advice request. Exceeding
 *                      it throws {@link AgentLoopLimitException} — loud, never a
 *                      silently partial answer.
 * @param memoryWindow  max messages retained per draft session's conversation
 *                      memory (user + assistant turns only; tool traffic is never
 *                      persisted).
 */
@Validated
@ConfigurationProperties(prefix = "readoption.agent")
public record AgentProperties(

        @NotBlank String model,

        @Min(1) @Max(20) int maxIterations,

        @Min(2) @Max(200) int memoryWindow
) {
}
