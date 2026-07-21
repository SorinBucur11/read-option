package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One pick from Sleeper's picks array. {@code isKeeper} is {@code Boolean},
 * nullable: null on all 150 probe picks — a non-null value is an unobserved
 * variant and halts the sync rather than being guessed at. The per-pick
 * {@code metadata} blob is NOT mapped; {@code player_id} joins to the player
 * table, which is the source of truth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperDraftPick(
        @JsonProperty("pick_no") int pickNo,
        int round,
        @JsonProperty("draft_slot") int draftSlot,
        @JsonProperty("player_id") String playerId,
        @JsonProperty("is_keeper") Boolean isKeeper,
        @JsonProperty("roster_id") Integer rosterId
) {}
