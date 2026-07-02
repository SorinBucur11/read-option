package app.readoption.customization;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/league/confirm}. Like refine, {@code current} is not
 * cascade-validated at the HTTP boundary — the confirm gate re-validates it in full
 * and refuses (409, no write) if anything BLOCKING remains.
 */
public record ConfirmRequest(
        @NotNull ParsedLeague current) {
}
