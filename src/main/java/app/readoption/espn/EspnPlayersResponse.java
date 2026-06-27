package app.readoption.espn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EspnPlayersResponse(List<Entry> players) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(Player player) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Player(
            long id,
            String fullName,
            Integer defaultPositionId,
            Integer proTeamId,
            Ownership ownership,
            List<StatEntry> stats
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ownership(Double averageDraftPosition) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatEntry(
            Integer seasonId,
            Integer statSourceId,
            Integer scoringPeriodId,
            Integer statSplitTypeId,
            Double appliedTotal,
            Map<String, Double> stats
    ) {}
}