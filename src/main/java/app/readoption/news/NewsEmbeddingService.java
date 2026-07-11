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
 * already stored, embed only the difference.
 *
 * <p>The store is fed in {@link #ADD_BATCH_SIZE}-document chunks, NOT one big
 * add: {@code PgVectorStore.doAdd} embeds its whole argument before inserting
 * anything, so a vendor 429 on an un-chunked full corpus discards every paid
 * embedding and lands zero rows (observed live: the seed corpus is ~3.6M tokens
 * against OpenAI's 1M tokens/min limit). Chunking makes completed chunks durable
 * — the anti-join resumes from them — and the bounded per-chunk backoff lets one
 * invocation pace itself through the rate window. A chunk that still fails after
 * {@link #MAX_ADD_ATTEMPTS} propagates loudly; everything landed so far stays.
 *
 * <p><b>No transaction here</b>: {@code vectorStore.add} holds the OpenAI
 * embedding call, and its upsert is idempotent by id — a partial build is
 * repaired by the next run, not by a rollback.
 *
 * <p>Derived-side cleaning: the landing row stays verbatim; the embedded text is
 * {@code headline + "\n" + HTML-stripped story}.
 */
@Service
public class NewsEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(NewsEmbeddingService.class);

    static final int ADD_BATCH_SIZE = 500;
    static final int MAX_ADD_ATTEMPTS = 5;

    /** Doubles per attempt: 5s, 10s, 20s, 40s — sized to a per-minute rate window. */
    private static final long INITIAL_BACKOFF_MS = 5_000;

    /** Test seam: retry tests must not sleep for real. */
    long initialBackoffMs = INITIAL_BACKOFF_MS;

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

        int chunks = (toEmbed.size() + ADD_BATCH_SIZE - 1) / ADD_BATCH_SIZE;
        for (int from = 0; from < toEmbed.size(); from += ADD_BATCH_SIZE) {
            List<Document> chunk = toEmbed.subList(from,
                    Math.min(from + ADD_BATCH_SIZE, toEmbed.size()));
            // The store embeds the chunk internally (token-batched) and upserts by id.
            addWithBackoff(chunk, from / ADD_BATCH_SIZE + 1, chunks);
        }

        NewsEmbedReport report = new NewsEmbedReport(
                byEmbeddingId.size(), toEmbed.size(), byEmbeddingId.size() - toEmbed.size());
        log.info("News embedding build [{}]: {} candidates, {} embedded, {} already current",
                modelTag, report.candidates(), report.embedded(), report.alreadyCurrent());
        return report;
    }

    /**
     * Provider-agnostic bounded retry: any chunk failure (429 is the expected
     * one) backs off and retries; a persistent failure (bad key, outage) burns
     * the attempts and then propagates loudly. Deliberately no provider exception
     * types here — D2's multi-provider goal keeps this service vendor-blind.
     */
    private void addWithBackoff(List<Document> chunk, int chunkNo, int chunks) {
        for (int attempt = 1; ; attempt++) {
            try {
                vectorStore.add(chunk);
                log.debug("Embedded chunk {}/{} ({} documents)", chunkNo, chunks, chunk.size());
                return;
            } catch (RuntimeException e) {
                if (attempt >= MAX_ADD_ATTEMPTS) {
                    log.error("Embedding chunk {}/{} failed after {} attempts: {}",
                            chunkNo, chunks, attempt, e.getMessage());
                    throw e;
                }
                long backoffMs = initialBackoffMs << (attempt - 1);
                log.warn("Embedding chunk {}/{} attempt {} failed ({}); retrying in {} ms",
                        chunkNo, chunks, attempt, e.getMessage(), backoffMs);
                pause(backoffMs);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("embedding build interrupted mid-backoff", interrupted);
        }
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
