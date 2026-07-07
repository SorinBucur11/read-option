package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The blob carries both {@code team} and {@code team_abbr}; {@code team} is the
 * populated one ({@code team_abbr} is null) — the mapper must read {@code team}.
 * Depth-chart and injury fields arrive in Sleeper's raw vocabulary and are landed
 * as-is (no enum normalization); all are null-tolerant — a healthy backup has the
 * injury trio null, a free agent may have everything null.
 */
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
        Boolean active,
        @JsonProperty("depth_chart_position") String depthChartPosition,
        @JsonProperty("depth_chart_order") Integer depthChartOrder,
        @JsonProperty("injury_status") String injuryStatus,
        @JsonProperty("injury_body_part") String injuryBodyPart,
        @JsonProperty("injury_notes") String injuryNotes
) {}
