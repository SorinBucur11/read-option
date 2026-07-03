package app.readoption.draft;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Starts a draft against a confirmed league config. Both {@code teamCount} and
 * {@code totalRounds} are snapshots of the config taken at session creation —
 * deliberately not request fields, so the draft can never disagree with the
 * league it belongs to. The cross-field rule {@code userSlot <= teamCount} is a
 * service check (it needs the config).
 */
public record StartDraftRequest(
        @NotNull Long leagueConfigId,
        @Min(1) int userSlot
) {
}