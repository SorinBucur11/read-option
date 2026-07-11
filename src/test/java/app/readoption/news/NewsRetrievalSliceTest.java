package app.readoption.news;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The whole derived path on the real pgvector image with the deterministic fake
 * {@code EmbeddingModel} (D2's test seam): landing rows → embedding build →
 * filtered similarity search. Pins the two load-bearing retrieval facts — the
 * player filter isolates one player's corpus, and {@code published} round-trips
 * the jsonb metadata intact (the A-4 citation fact).
 *
 * <p>The store's JdbcTemplate rides the test-managed transaction
 * (DataSourceUtils binds it to the same connection), so vector writes roll back
 * with each test; the up-front DELETE is belt-and-braces against any row that
 * ever lands outside a rolled-back test.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("news retrieval slice — fake embeddings, real pgvector, player-scoped search")
class NewsRetrievalSliceTest extends AbstractPostgresTest {

    private static final String TAG = "text-embedding-3-small";

    @Autowired private PlayerNewsRepository newsRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private DataSource dataSource;

    private JdbcTemplate jdbcTemplate;
    private PgVectorStore vectorStore;
    private NewsEmbeddingService embeddingService;
    private PlayerNewsSearchService searchService;

    @BeforeEach
    void buildTheStack() {
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DELETE FROM news_embedding");

        vectorStore = PgVectorStore.builder(jdbcTemplate, new FakeEmbeddingModel())
                .vectorTableName("news_embedding")
                .initializeSchema(false)
                .vectorTableValidationsEnabled(true)
                .dimensions(1536)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .build();
        vectorStore.afterPropertiesSet();

        NewsProperties properties = new NewsProperties(TAG, 5);
        embeddingService = new NewsEmbeddingService(newsRepository, vectorStore,
                jdbcTemplate, properties);
        searchService = new PlayerNewsSearchService(vectorStore, playerRepository, properties);
    }

    private void seedPlayer(String id, String name, String espnId) {
        Player player = player(id, name, "QB", "KC", true);
        player.setEspnId(espnId);
        playerRepository.save(player);
    }

    private void seedNews(String newsId, String playerId, String headline, String story,
                          String published) {
        newsRepository.save(PlayerNews.builder()
                .source("espn").newsId(newsId).playerId(playerId).espnPlayerId(1L)
                .headline(headline).story(story)
                .published(Instant.parse(published))
                .premium(false).sourcePayload("{}")
                .build());
    }

    @Test
    @DisplayName("player filter isolates the corpus; published round-trips; rerun is idempotent")
    void playerScopedRetrievalRoundTrip() {
        seedPlayer("4046", "Patrick Mahomes", "3139477");
        seedPlayer("4866", "Saquon Barkley", "3929630");
        seedNews("1", "4046", "Mahomes cleared for camp",
                "<p>Reid said Mahomes should be able to do some things in camp.</p>",
                "2026-06-11T15:08:41Z");
        seedNews("2", "4046", "Mahomes limited at OTAs",
                "He's been limited to individual drills and 7-on-7s.",
                "2026-05-28T18:12:47Z");
        seedNews("3", "4866", "Barkley rests a hamstring",
                "Precautionary rest, per the coaching staff.",
                "2026-06-01T12:00:00Z");

        NewsEmbeddingService.NewsEmbedReport first = embeddingService.build();
        assertThat(first.candidates()).isEqualTo(3);
        assertThat(first.embedded()).isEqualTo(3);

        // Idempotence on the REAL store: the rerun finds every id already present.
        NewsEmbeddingService.NewsEmbedReport rerun = embeddingService.build();
        assertThat(rerun.embedded()).isZero();
        assertThat(rerun.alreadyCurrent()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM news_embedding", Long.class)).isEqualTo(3);

        PlayerNewsView view = searchService.searchForPlayer("4046", "knee injury recovery");

        assertThat(view.note()).isNull();
        assertThat(view.items()).hasSize(2);   // Barkley's item MUST NOT leak in
        assertThat(view.items()).extracting(PlayerNewsView.NewsItem::published)
                .containsExactly("2026-06-11T15:08:41Z", "2026-05-28T18:12:47Z");
        assertThat(view.items().get(0).headline()).isEqualTo("Mahomes cleared for camp");
        assertThat(view.items().get(0).story())
                .isEqualTo("Reid said Mahomes should be able to do some things in camp.")
                .doesNotContain("<p>");
    }

    @Test
    @DisplayName("another model generation's rows never surface under the current tag")
    void modelGenerationsDoNotCrossRead() {
        seedPlayer("4046", "Patrick Mahomes", "3139477");
        seedNews("1", "4046", "Mahomes cleared for camp", "story", "2026-06-11T15:08:41Z");
        embeddingService.build();

        // Same corpus embedded under a hypothetical OLD tag: rows exist, filter must exclude them.
        NewsProperties oldGeneration = new NewsProperties("text-embedding-ada-002", 5);
        new NewsEmbeddingService(newsRepository, vectorStore, jdbcTemplate, oldGeneration).build();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM news_embedding", Long.class)).isEqualTo(2);

        PlayerNewsView current = searchService.searchForPlayer("4046", "camp status");
        assertThat(current.items()).hasSize(1);

        PlayerNewsSearchService oldReader = new PlayerNewsSearchService(
                vectorStore, playerRepository, oldGeneration);
        assertThat(oldReader.searchForPlayer("4046", "camp status").items()).hasSize(1);
    }

    @Test
    @DisplayName("a queryable player with an empty corpus reads NO_NEWS_FOUND end to end")
    void emptyCorpusReadsNoNewsFound() {
        seedPlayer("4046", "Patrick Mahomes", "3139477");

        PlayerNewsView view = searchService.searchForPlayer("4046", "anything");

        assertThat(view.note()).isEqualTo(NewsVocabulary.NO_NEWS_FOUND);
        assertThat(view.items()).isNull();
    }
}
