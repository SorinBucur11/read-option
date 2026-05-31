package app.readoption.sleeper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SleeperProjectionData(

        // Scoring stats — Double from the API, same as the stats endpoint.
        @JsonProperty("pass_yd")   Double passingYards,
        @JsonProperty("pass_td")   Double passingTd,
        @JsonProperty("pass_int")  Double interceptions,
        @JsonProperty("rush_yd")   Double rushingYards,
        @JsonProperty("rush_td")   Double rushingTd,
        @JsonProperty("rec")       Double receptions,
        @JsonProperty("rec_yd")    Double receivingYards,
        @JsonProperty("rec_td")    Double receivingTd,
        @JsonProperty("fum_lost")  Double fumblesLost,
        @JsonProperty("pass_2pt")  Double pass2pt,
        @JsonProperty("rush_2pt")  Double rush2pt,
        @JsonProperty("rec_2pt")   Double rec2pt,

        // ADP — persisted. The 999 "unranked" sentinel becomes null in the sync.
        @JsonProperty("adp_std")       Double adpStd,
        @JsonProperty("adp_half_ppr")  Double adpHalfPpr,
        @JsonProperty("adp_ppr")       Double adpPpr,

        // Provider's own points — NOT persisted. Parsed only so Day 3 can
        // cross-check our ScoringService against rotowire's numbers.
        @JsonProperty("pts_std")       Double ptsStd,
        @JsonProperty("pts_half_ppr")  Double ptsHalfPpr,
        @JsonProperty("pts_ppr")       Double ptsPpr
) {
}