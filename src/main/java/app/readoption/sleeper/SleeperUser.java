package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The slice of Sleeper's user object the draft sync needs: username resolves to
 * the {@code user_id} that keys {@code draft_order} in {@link SleeperDraft}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperUser(
        @JsonProperty("user_id") String userId,
        String username
) {}
