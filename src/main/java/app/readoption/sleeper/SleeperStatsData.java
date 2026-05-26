package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperStatsData(
        @JsonProperty("gp") Double gamesPlayed,
        @JsonProperty("pass_att") Double passAttempts,
        @JsonProperty("pass_cmp") Double passesCompleted,
        @JsonProperty("pass_yd") Double passingYards,
        @JsonProperty("pass_td") Double passingTd,
        @JsonProperty("pass_int") Double interceptions,
        @JsonProperty("rush_att") Double rushingAttempts,
        @JsonProperty("rush_yd") Double rushingYards,
        @JsonProperty("rush_td") Double rushingTd,
        @JsonProperty("rec_tgt") Double targets,
        @JsonProperty("rec") Double receptions,
        @JsonProperty("rec_yd") Double receivingYards,
        @JsonProperty("rec_td") Double receivingTd,
        @JsonProperty("fum_lost") Double fumblesLost,
        @JsonProperty("pass_2pt") Double pass2pt,
        @JsonProperty("rush_2pt") Double rush2pt,
        @JsonProperty("rec_2pt") Double rec2pt
) {}