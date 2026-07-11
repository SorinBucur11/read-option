package app.readoption.news;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The deterministic UUID (idempotent upsert + coexisting model generations rest
 * on it), the anti-join resume semantics, and the derived-side document
 * construction — headline+cleaned(story) text, dated metadata (A-4).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NewsEmbeddingService — deterministic ids, anti-join resume, document construction")
class NewsEmbeddingServiceTest {

    private static final String TAG = "text-embedding-3-small";

    @Mock private PlayerNewsRepository newsRepository;
    @Mock private VectorStore vectorStore;
    @Mock private JdbcTemplate jdbcTemplate;

    private NewsEmbeddingService service() {
        return new NewsEmbeddingService(newsRepository, vectorStore, jdbcTemplate,
                new NewsProperties(TAG, 5));
    }

    private static PlayerNews news(String newsId, String playerId, String headline, String story) {
        return PlayerNews.builder()
                .source("espn").newsId(newsId).playerId(playerId).espnPlayerId(1L)
                .headline(headline).story(story)
                .published(Instant.parse("2026-06-11T15:08:41Z"))
                .premium(false).sourcePayload("{}")
                .build();
    }

    @Test
    @DisplayName("same (source, newsId, tag) always derives the same UUID")
    void uuidIsDeterministic() {
        UUID first = NewsEmbeddingService.embeddingId("espn", "63134328", TAG);
        UUID second = NewsEmbeddingService.embeddingId("espn", "63134328", TAG);

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a model-tag change derives a DIFFERENT UUID - generations coexist, never collide")
    void tagChangeChangesUuid() {
        UUID current = NewsEmbeddingService.embeddingId("espn", "63134328", TAG);
        UUID swapped = NewsEmbeddingService.embeddingId("espn", "63134328", "text-embedding-3-large");
        UUID otherSource = NewsEmbeddingService.embeddingId("rotowire", "63134328", TAG);

        assertThat(current).isNotEqualTo(swapped);
        assertThat(current).isNotEqualTo(otherSource);
    }

    @Test
    @DisplayName("anti-join: only rows missing under the current tag are embedded; rerun resumes")
    void antiJoinEmbedsOnlyTheDifference() {
        PlayerNews already = news("1", "4046", "Mahomes cleared", "story A");
        PlayerNews missing = news("2", "4046", "Mahomes limited", "story B");
        when(newsRepository.findAll()).thenReturn(List.of(already, missing));
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class)))
                .thenReturn(List.of(NewsEmbeddingService.embeddingId("espn", "1", TAG)));

        NewsEmbeddingService.NewsEmbedReport report = service().build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batch = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(batch.capture());
        assertThat(batch.getValue()).hasSize(1);
        assertThat(batch.getValue().get(0).getId())
                .isEqualTo(NewsEmbeddingService.embeddingId("espn", "2", TAG).toString());
        assertThat(report.candidates()).isEqualTo(2);
        assertThat(report.embedded()).isEqualTo(1);
        assertThat(report.alreadyCurrent()).isEqualTo(1);
    }

    @Test
    @DisplayName("document: text = headline + cleaned story; metadata carries the dated citation facts")
    void documentConstructionIsDatedAndCleaned() {
        PlayerNews row = news("63134328", "4046", "Mahomes cleared",
                "<p>Reid said he&#39;s &quot;on course&quot; &amp; ready.</p>");
        when(newsRepository.findAll()).thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of());

        service().build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batch = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(batch.capture());
        Document document = batch.getValue().get(0);
        assertThat(document.getText())
                .isEqualTo("Mahomes cleared\nReid said he's \"on course\" & ready.");
        assertThat(document.getMetadata())
                .containsEntry("player_id", "4046")
                .containsEntry("espn_news_id", "63134328")
                .containsEntry("published", "2026-06-11T15:08:41Z")
                .containsEntry("headline", "Mahomes cleared")
                .containsEntry("embedding_model", TAG);
    }

    @Test
    @DisplayName("a null story embeds the headline alone - no dangling separator")
    void nullStoryEmbedsHeadlineOnly() {
        when(newsRepository.findAll()).thenReturn(List.of(news("7", "9488", "JSN traded", null)));
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class))).thenReturn(List.of());

        service().build();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> batch = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(batch.capture());
        assertThat(batch.getValue().get(0).getText()).isEqualTo("JSN traded");
    }

    @Test
    @DisplayName("fully current corpus: the vendor is never called")
    void fullyCurrentCorpusSkipsTheVendor() {
        PlayerNews row = news("1", "4046", "Mahomes cleared", "story");
        when(newsRepository.findAll()).thenReturn(List.of(row));
        when(jdbcTemplate.queryForList(anyString(), eq(UUID.class)))
                .thenReturn(List.of(NewsEmbeddingService.embeddingId("espn", "1", TAG)));

        NewsEmbeddingService.NewsEmbedReport report = service().build();

        verify(vectorStore, never()).add(anyList());
        assertThat(report.embedded()).isZero();
        assertThat(report.alreadyCurrent()).isEqualTo(1);
    }
}
