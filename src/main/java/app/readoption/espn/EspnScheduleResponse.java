package app.readoption.espn;

import java.util.List;

/**
 * The slice of ESPN's per-team schedule payload the sync needs — nothing more.
 * {@code seasonType.type} is load-bearing: the events array carries preseason
 * games (type 1) whose week numbers collide with regular-season weeks; only
 * type 2 may land. Field names match the wire, so no {@code @JsonProperty} needed
 * (Spring's converter ignores the payload's many unknown properties).
 */
public record EspnScheduleResponse(List<Event> events) {

    public record Event(Week week, SeasonType seasonType, List<Competition> competitions) {
    }

    public record Week(Integer number) {
    }

    public record SeasonType(Integer type) {
    }

    public record Competition(List<Competitor> competitors) {
    }

    public record Competitor(String homeAway, Team team) {
    }

    public record Team(String abbreviation) {
    }
}
