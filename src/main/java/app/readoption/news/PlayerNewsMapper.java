package app.readoption.news;

import app.readoption.espn.EspnNewsResponse;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Wire item → landing entity. Required fields ({@code id}, {@code headline},
 * {@code published}) throw on absence or malformation — the sync's
 * skip-never-deletes posture turns that into "this player's fetch failed, run
 * continues", never a silently partial row. {@code story} stays verbatim, HTML
 * and all; cleaning is the embedding build's concern.
 */
@Component
public class PlayerNewsMapper {

    public static final String SOURCE = "espn";

    private final ObjectMapper objectMapper;

    public PlayerNewsMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlayerNews toEntity(String playerId, long espnPlayerId, EspnNewsResponse.Item item) {
        if (item.id() == null || item.headline() == null || item.published() == null) {
            throw new IllegalStateException("malformed ESPN news item for player " + playerId
                    + ": id/headline/published missing");
        }
        return PlayerNews.builder()
                .source(SOURCE)
                .newsId(String.valueOf(item.id()))
                .playerId(playerId)
                .espnPlayerId(espnPlayerId)
                .headline(item.headline())
                .story(item.story())
                .published(Instant.parse(item.published()))
                .lastModified(item.lastModified() != null ? Instant.parse(item.lastModified()) : null)
                .premium(Boolean.TRUE.equals(item.premium()))
                // The typed item, re-serialized — the source_payload convention
                // (player_projection_raw precedent). NOT NULL column: a serialize
                // failure fails this player loudly rather than landing a null audit trail.
                .sourcePayload(objectMapper.writeValueAsString(item))
                .build();
    }
}
