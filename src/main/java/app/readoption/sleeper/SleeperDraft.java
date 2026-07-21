package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The slice of Sleeper's draft object the sync consumes. Probe-pinned facts
 * (phase-5.0 probes p3/p4 + the pre_draft/drafting pair):
 *
 * <ul>
 *   <li>{@code draftOrder} is <b>null until status flips to {@code drafting}</b> —
 *       never assume it on a pre_draft object.</li>
 *   <li>{@code reversalRound} is {@code Integer}, never {@code int}: the field is
 *       ABSENT on quick-create drafts and an explicit {@code 0} on league drafts.
 *       Absent and zero are distinct observations, and the 3RR halt log must be
 *       able to say which it saw.</li>
 *   <li>{@code slot_to_roster_id} and {@code metadata} are deliberately NOT
 *       mapped: v1 operates entirely in slot-space, and 5.1 owns
 *       {@code metadata.scoring_type}. Do not map fields nothing consumes.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperDraft(
        @JsonProperty("draft_id") String draftId,
        String status,
        String type,
        String season,
        Settings settings,
        @JsonProperty("draft_order") Map<String, Integer> draftOrder
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Settings(
            int teams,
            int rounds,
            @JsonProperty("pick_timer") int pickTimer,
            @JsonProperty("reversal_round") Integer reversalRound
    ) {}
}
