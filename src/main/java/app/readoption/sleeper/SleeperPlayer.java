package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SleeperPlayer(
        @JsonProperty("player_id") String playerId,
        @JsonProperty("first_name") String firstName,
        @JsonProperty("last_name") String lastName,
        @JsonProperty("full_name") String fullName,
        String position,
        String team,
        Integer age,
        @JsonProperty("years_exp") Integer yearsExp,
        String status,
        Boolean active
) {}
