package app.readoption.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The derived embedding build: {@code player_news} → {@code news_embedding},
 * retryable and idempotent. The deterministic UUID
 * ({@code nameUUIDFromBytes(source:newsId:modelTag)}) makes the whole build an
 * anti-join: compute every row's id under the current model tag, skip the ones
 * already stored, embed only the difference. A vendor failure (429/outage) fails
 * the batch loudly and touches nothing upstream — rerun resumes from the anti-join.
 *
 * <p><b>No transaction here</b>: {@code vectorStore.add} holds the OpenAI
 * embedding call, and its upsert is idempotent by id — a partial batch is
 * repaired by the next run, not by a rollback.
 *
 * <p>Derived-side cleaning: the landing row stays verbatim; the embedded text is
 * {@code headline + "\n" + HTML-stripped story}.
 */
@Service
public class NewsEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(NewsEmbeddingService.class);

    private final PlayerNewsRepository newsRepository;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final NewsProperties properties;

    public NewsEmbeddingService(PlayerNewsRepository newsRepository,
                                VectorStore vectorStore,
                                JdbcTemplate jdbcTemplate,
                                NewsProperties properties) {
        this.newsRepository = newsRepository;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public NewsEmbedReport build() {
        String modelTag = properties.embeddingModelTag();
        List<PlayerNews> corpus = newsRepository.findAll();

        Map<UUID, PlayerNews> byEmbeddingId = new LinkedHashMap<>();
        for (PlayerNews row : corpus) {
            byEmbeddingId.put(embeddingId(row.getSource(), row.getNewsId(), modelTag), row);
        }

        // One query for what already exists; ids of other model generations can
        // never collide with ids computed under the current tag, so a plain id
        // scan is a correct anti-join.
        Set<UUID> existing = Set.copyOf(
                jdbcTemplate.queryForList("SELECT id FROM news_embedding", UUID.class));

        List<Document> toEmbed = byEmbeddingId.entrySet().stream()
                .filter(entry -> !existing.contains(entry.getKey()))
                .map(entry -> toDocument(entry.getKey(), entry.getValue(), modelTag))
                .toList();

        if (!toEmbed.isEmpty()) {
            // The store embeds internally (batched) and upserts by id.
            vectorStore.add(toEmbed);
        }

        NewsEmbedReport report = new NewsEmbedReport(
                byEmbeddingId.size(), toEmbed.size(), byEmbeddingId.size() - toEmbed.size());
        log.info("News embedding build [{}]: {} candidates, {} embedded, {} already current",
                modelTag, report.candidates(), report.embedded(), report.alreadyCurrent());
        return report;
    }

    /** Deterministic id: same (source, newsId, modelTag) always lands on the same row. */
    static UUID embeddingId(String source, String newsId, String modelTag) {
        return UUID.nameUUIDFromBytes(
                (source + ":" + newsId + ":" + modelTag).getBytes(StandardCharsets.UTF_8));
    }

    private Document toDocument(UUID id, PlayerNews row, String modelTag) {
        String story = cleaned(row.getStory());
        String text = story.isEmpty() ? row.getHeadline() : row.getHeadline() + "\n" + story;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("player_id", row.getPlayerId());
        metadata.put("espn_news_id", row.getNewsId());
        metadata.put("published", row.getPublished().toString());   // ISO-8601, the citation fact (A-4)
        metadata.put("headline", row.getHeadline());
        metadata.put("embedding_model", modelTag);

        return Document.builder()
                .id(id.toString())
                .text(text)
                .metadata(metadata)
                .build();
    }

    /**
     * Minimal HTML strip for embedding text: tags out, the few entities ESPN's
     * blurbs actually carry unescaped, whitespace collapsed. Not a sanitizer —
     * the verbatim story stays in the landing row.
     */
    static String cleaned(String story) {
        if (story == null || story.isBlank()) {
            return "";
        }
        return story
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record NewsEmbedReport(int candidates, int embedded, int alreadyCurrent) {
    }
}
