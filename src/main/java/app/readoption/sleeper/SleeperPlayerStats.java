package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperPlayerStats(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("team") String team,
        @JsonProperty("season") String season,
        @JsonProperty("stats") SleeperStatsData stats
) {}