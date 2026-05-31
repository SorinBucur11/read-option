package app.readoption.sleeper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class SleeperClient {

    private static final Logger log = LoggerFactory.getLogger(SleeperClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String playersUrl;
    private final String statsBaseUrl;
    private final String projectionsBaseUrl;

    public SleeperClient(@Value("${sleeper.api.players-url}") String playersUrl,
                         @Value("${sleeper.api.stats-url}") String statsBaseUrl,
                         @Value("${sleeper.api.projections-url}") String projectionsBaseUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.playersUrl = playersUrl;
        this.statsBaseUrl = statsBaseUrl;
        this.projectionsBaseUrl = projectionsBaseUrl;
    }

    public Map<String, SleeperPlayer> fetchAllPlayers() {
        log.info("Fetching all NFL players from Sleeper API...");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(playersUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Sleeper API returned " + response.statusCode());
            }

            Map<String, SleeperPlayer> players = mapper.readValue(
                    response.body(),
                    new TypeReference<Map<String, SleeperPlayer>>() {
                    }
            );

            log.info("Fetched {} players from Sleeper API", players.size());
            return players;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch players from Sleeper API", e);
        }
    }

    public List<SleeperPlayerStats> fetchStats(int season) {
        String url = statsBaseUrl + "/" + season + "?season_type=regular";
        log.info("Fetching stats from Sleeper for season {}: {}", season, url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Sleeper stats API returned " + response.statusCode());
            }

            List<SleeperPlayerStats> stats = mapper.readValue(
                    response.body(),
                    new TypeReference<List<SleeperPlayerStats>>() {}
            );

            log.info("Fetched {} player stat lines for season {}", stats.size(), season);
            return stats;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch stats from Sleeper API", e);
        }
    }

    public List<SleeperProjection> fetchProjections(int season) {
        String url = projectionsBaseUrl + "/" + season + "?season_type=regular";
        log.info("Fetching projections from Sleeper for season {}: {}", season, url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Sleeper projections API returned " + response.statusCode());
            }

            List<SleeperProjection> projections = mapper.readValue(
                    response.body(),
                    new TypeReference<List<SleeperProjection>>() {}
            );

            log.info("Fetched {} player projections for season {}", projections.size(), season);
            return projections;

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to fetch projections from Sleeper API", e);
        }
    }
}
