package app.readoption.agent.dto;

/**
 * The advice plus the loop's own instrumentation — round trips, cumulative token
 * spend, and wall-clock latency. Surfacing these is the point of owning the loop:
 * an on-the-clock advisor must be observable per request, not just in aggregate.
 *
 * @param iterations tool-execution round trips (0 = the model answered without tools)
 * @param totalTokens cumulative prompt + completion tokens across every model call
 */
public record AdviceResponse(
        String advice,
        int iterations,
        long totalTokens,
        long latencyMs
) {
}
