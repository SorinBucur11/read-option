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
import java.util.Map;

@Component
public class SleeperClient {

    private static final Logger log = LoggerFactory.getLogger(SleeperClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String playersUrl;

    public SleeperClient(@Value("${sleeper.api.players-url}") String playersUrl) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.playersUrl = playersUrl;
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
}
