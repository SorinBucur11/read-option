package app.readoption.sleeper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Sleeper live-draft-sync knobs (Phase 5.0), validated at the startup boundary —
 * the {@code AgentProperties} idiom. {@code username} is deliberately NOT
 * {@code @NotBlank}: the app must boot without it; a sync <i>start</i> without it
 * fails fast instead (the runner enforces this, loudly).
 *
 * @param username the Sleeper username whose {@code user_id} must appear in a
 *                 draft's {@code draft_order} for a sync to link.
 * @param sync     poll cadence and failure tolerance for the sync loop.
 */
@Validated
@ConfigurationProperties(prefix = "readoption.sleeper")
public record SleeperSyncProperties(

        String username,

        @Valid @NotNull Sync sync
) {

    /**
     * @param pollInterval         sleep between polls of a watched draft.
     * @param errorBudget          consecutive poll failures tolerated before the
     *                             loop halts loudly with status ERROR. A success
     *                             resets the counter; validation-gate halts ignore
     *                             the budget.
     * @param completionGracePolls consecutive polls tolerated with the draft
     *                             complete at Sleeper but the picks array short of
     *                             teams x rounds, before the loop halts loudly with
     *                             status ERROR. Independent of {@code errorBudget};
     *                             reset by any poll whose count is honest.
     */
    public record Sync(

            @NotNull Duration pollInterval,

            @Min(1) @Max(100) int errorBudget,

            @Min(1) @Max(200) int completionGracePolls
    ) {}
}
