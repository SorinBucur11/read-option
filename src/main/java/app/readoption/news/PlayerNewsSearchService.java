package app.readoption.news;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * The retrieval read behind {@code searchPlayerNews}: similarity search over the
 * derived {@code news_embedding} table, hard-filtered to one player AND the
 * current embedding-model generation. {@code topK} is a server-side property —
 * the tool schema exposes no knob for it.
 *
 * <p>Degradation vocabulary ({@link NewsVocabulary}) is owned here, the news
 * layer's single home: no espn_id → the source was never queryable for this
 * player; empty retrieval → queryable but quiet. An unknown playerId throws —
 * a model-supplied bad argument, the error-tool-response self-correction path
 * (same posture as the other five tools).
 */
@Service
public class PlayerNewsSearchService {

    private final VectorStore vectorStore;
    private final PlayerRepository playerRepository;
    private final NewsProperties properties;

    public PlayerNewsSearchService(VectorStore vectorStore,
                                   PlayerRepository playerRepository,
                                   NewsProperties properties) {
        this.vectorStore = vectorStore;
        this.playerRepository = playerRepository;
        this.properties = properties;
    }

    public PlayerNewsView searchForPlayer(String playerId, String query) {
        Player player = playerRepository.findById(playerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown playerId '" + playerId
                        + "' - use findPlayer to resolve a name to a playerId"));
        if (player.getEspnId() == null || player.getEspnId().isBlank()) {
            return PlayerNewsView.degraded(playerId, NewsVocabulary.NEWS_UNAVAILABLE_NO_ESPN_ID);
        }

        FilterExpressionBuilder filter = new FilterExpressionBuilder();
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(properties.topK())
                .filterExpression(filter.and(
                        filter.eq("player_id", playerId),
                        filter.eq("embedding_model", properties.embeddingModelTag())).build())
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        if (documents == null || documents.isEmpty()) {
            return PlayerNewsView.degraded(playerId, NewsVocabulary.NO_NEWS_FOUND);
        }

        List<PlayerNewsView.NewsItem> items = documents.stream()
                .map(PlayerNewsSearchService::toItem)
                // Reverse-chronology AFTER retrieval; the ISO-8601 strings we wrote
                // sort lexicographically = chronologically (same format, same zone).
                .sorted(Comparator.comparing(PlayerNewsView.NewsItem::published).reversed())
                .toList();
        return new PlayerNewsView(playerId, null, items);
    }

    private static PlayerNewsView.NewsItem toItem(Document document) {
        String published = metadataString(document, "published");
        String headline = metadataString(document, "headline");
        // Document text is headline + "\n" + cleaned story (NewsEmbeddingService's
        // construction); peel the headline back off rather than re-reading the
        // landing row — one retrieval, no second query.
        String text = document.getText() != null ? document.getText() : "";
        String story = text.startsWith(headline + "\n")
                ? text.substring(headline.length() + 1)
                : text;
        return new PlayerNewsView.NewsItem(published, headline, story);
    }

    private static String metadataString(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value != null ? value.toString() : "";
    }
}
