package app.readoption.customization;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/league/refine}. {@code current} is deliberately <b>not</b>
 * cascade-validated ({@code @NotNull} without {@code @Valid}): mid-repair it is
 * expected to be partial — its problems are reported as {@link ParseResult} issues,
 * not rejected at the HTTP boundary.
 *
 * @param turn 1-based refine-turn counter, carried by the client because the API is
 *             stateless; the server enforces the {@code maxRefineTurns} cap on it.
 */
public record RefineRequest(
        @NotNull ParsedLeague current,
        @NotBlank @Size(max = 2000) String correction,
        @Min(1) int turn) {
}
