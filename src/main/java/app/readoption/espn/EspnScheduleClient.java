package app.readoption.espn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * ESPN's <b>site</b> API host ({@code site.api.espn.com}) — a different host and
 * auth posture from the fantasy API {@link EspnClient} talks to: no
 * {@code X-Fantasy-Filter}, no header auth at all. Deliberately its own small
 * client rather than an overload on {@code EspnClient}.
 */
@Component
public class EspnScheduleClient {

    private static final Logger log = LoggerFactory.getLogger(EspnScheduleClient.class);

    private static final String URL_TEMPLATE =
            "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams/%s/schedule?season=%d";

    private final RestClient restClient;

    public EspnScheduleClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /** Fetches one team's schedule by its ESPN abbreviation. Never returns null events. */
    public EspnScheduleResponse fetchSchedule(String espnAbbrev, int season) {
        String url = URL_TEMPLATE.formatted(espnAbbrev, season);
        log.debug("Fetching ESPN schedule: team={}, season={}", espnAbbrev, season);

        EspnScheduleResponse response;
        try {
            response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(EspnScheduleResponse.class);
        } catch (RestClientException e) {
            log.error("ESPN schedule fetch failed for {}: {}", espnAbbrev, e.getMessage());
            throw new EspnUnavailableException(
                    "ESPN schedule source is unavailable for " + espnAbbrev, e);
        }

        if (response == null || response.events() == null) {
            log.warn("ESPN returned no schedule events for {}", espnAbbrev);
            return new EspnScheduleResponse(List.of());
        }
        return response;
    }
}
