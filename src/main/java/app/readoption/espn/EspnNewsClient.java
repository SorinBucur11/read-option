package app.readoption.espn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * ESPN's <b>site</b> API host ({@code site.api.espn.com}) — same host and auth
 * posture as {@link EspnScheduleClient}: no {@code X-Fantasy-Filter}, no header
 * auth at all. Deliberately its own small client (one endpoint, one caller),
 * matching the one-client-per-endpoint pattern of the espn package.
 */
@Component
public class EspnNewsClient {

    private static final Logger log = LoggerFactory.getLogger(EspnNewsClient.class);

    /**
     * limit=50 is the page size the 4.4 source audit probed; the feed retains far
     * fewer items per player (A-5: as few as 4–5), so one page is the whole feed.
     */
    private static final String URL_TEMPLATE =
            "https://site.api.espn.com/apis/fantasy/v2/games/ffl/news/players?limit=50&playerId=%d";

    private final RestClient restClient;

    public EspnNewsClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    /** Fetches one player's news feed by ESPN player id. Never returns a null feed. */
    public EspnNewsResponse fetchPlayerNews(long espnPlayerId) {
        String url = URL_TEMPLATE.formatted(espnPlayerId);
        log.debug("Fetching ESPN player news: espnPlayerId={}", espnPlayerId);

        EspnNewsResponse response;
        try {
            response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(EspnNewsResponse.class);
        } catch (RestClientException e) {
            log.error("ESPN news fetch failed for espnPlayerId {}: {}", espnPlayerId, e.getMessage());
            throw new EspnUnavailableException(
                    "ESPN news source is unavailable for espn player " + espnPlayerId, e);
        }

        if (response == null || response.feed() == null) {
            log.warn("ESPN returned no news feed for espnPlayerId {}", espnPlayerId);
            return new EspnNewsResponse(List.of());
        }
        return response;
    }
}
