package app.readoption.espn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class EspnClient {

    private static final Logger log = LoggerFactory.getLogger(EspnClient.class);

    private static final String URL_TEMPLATE =
            "https://lm-api-reads.fantasy.espn.com/apis/v3/games/ffl/seasons/%d" +
                    "/segments/0/leaguedefaults/3?view=kona_player_info";

    private final RestClient restClient;
    private final int limit;

    public EspnClient(@Value("${readoption.espn.player-limit:400}") int limit) {
        this.limit = limit;
        this.restClient = RestClient.create();
    }

    public List<EspnPlayersResponse.Entry> fetchProjections(int season) {
        String url = URL_TEMPLATE.formatted(season);
        // X-Fantasy-Filter: a JSON query passed as an HTTP header. limit REQUIRES
        // an accompanying sort (we confirmed this empirically); sort by ownership
        // descending so the bounded pull is the most-drafted players.
        String filter = """
                {"players":{"limit":%d,"sortPercOwned":{"sortPriority":1,"sortAsc":false}}}"""
                .formatted(limit);

        log.info("Fetching ESPN projections: season={}, limit={}", season, limit);

        EspnPlayersResponse response;
        try {
            response = restClient.get()
                    .uri(url)
                    .header("X-Fantasy-Filter", filter)
                    .retrieve()
                    .body(EspnPlayersResponse.class);
        } catch (org.springframework.web.client.RestClientException e) {
            log.error("ESPN projections fetch failed: {}", e.getMessage());
            throw new EspnUnavailableException("ESPN projections source is unavailable", e);
        }

        if (response == null || response.players() == null) {
            log.warn("ESPN returned no players");
            return List.of();
        }
        log.info("ESPN returned {} player entries", response.players().size());
        return response.players();
    }
}