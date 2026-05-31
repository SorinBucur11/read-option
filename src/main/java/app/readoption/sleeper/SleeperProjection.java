package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperProjection(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("team") String team,
        @JsonProperty("company") String company,
        @JsonProperty("stats") SleeperProjectionData stats
) {
}