package app.readoption.news;

import app.readoption.AbstractPostgresTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/**
 * The writer's insert-only dedup against the real V15 schema (also the empirical
 * pin that the {@code PlayerNews} entity validates against the TIMESTAMPTZ/JSONB
 * DDL). A re-sync re-delivers the same {@code (source, news_id)} items — zero
 * duplicates may land, and the first-landed row must survive untouched.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@DisplayName("player_news persistence — insert-only dedup, re-sync lands zero duplicates")
class PlayerNewsRepositoryTest extends AbstractPostgresTest {

    @Autowired private PlayerNewsRepository newsRepository;
    @Autowired private TestEntityManager entityManager;

    private PlayerNewsWriter writer() {
        return new PlayerNewsWriter(newsRepository);
    }

    private static PlayerNews news(String newsId, String playerId, String headline) {
        return PlayerNews.builder()
                .source("espn")
                .newsId(newsId)
                .playerId(playerId)
                .espnPlayerId(3139477L)
                .headline(headline)
                .story("<p>verbatim story</p>")
                .published(Instant.parse("2026-06-11T15:08:41Z"))
                .lastModified(Instant.parse("2026-06-11T15:08:41Z"))
                .premium(false)
                .sourcePayload("{\"id\":" + newsId + ",\"type\":\"Rotowire\"}")
                .build();
    }

    @Test
    @DisplayName("re-sync with the same (source, news_id) inserts zero duplicates and never updates")
    void reSyncInsertsZeroDuplicates() {
        PlayerNewsWriter writer = writer();

        PlayerNewsWriter.InsertOutcome first = writer.insertNew(List.of(
                news("100", "4046", "Mahomes cleared"),
                news("101", "4046", "Mahomes limited")));
        entityManager.flush();

        // Re-sync: same ids, one changed headline (the source edited its blurb) +
        // one genuinely new item. The changed headline must NOT land — insert-only.
        PlayerNewsWriter.InsertOutcome second = writer.insertNew(List.of(
                news("100", "4046", "Mahomes cleared - EDITED"),
                news("101", "4046", "Mahomes limited"),
                news("102", "4046", "Mahomes practices in full")));
        entityManager.flush();
        entityManager.clear();

        assertThat(first.inserted()).isEqualTo(2);
        assertThat(first.skippedExisting()).isZero();
        assertThat(second.inserted()).isEqualTo(1);
        assertThat(second.skippedExisting()).isEqualTo(2);

        List<PlayerNews> landed = newsRepository.findAll().stream()
                .filter(row -> row.getPlayerId().equals("4046"))
                .toList();
        assertThat(landed).hasSize(3);
        assertThat(landed)
                .filteredOn(row -> row.getNewsId().equals("100"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getHeadline()).isEqualTo("Mahomes cleared");   // first sighting wins
                    assertThat(row.getCreatedAt()).isNotNull();
                    assertThat(row.getPublished()).isEqualTo(Instant.parse("2026-06-11T15:08:41Z"));
                });
    }

    @Test
    @DisplayName("a within-batch duplicate id collapses before the flush - no PK collision")
    void withinBatchDuplicateCollapses() {
        PlayerNewsWriter.InsertOutcome outcome = writer().insertNew(List.of(
                news("200", "9488", "JSN item"),
                news("200", "9488", "JSN item, doubled in the feed")));
        entityManager.flush();

        assertThat(outcome.inserted()).isEqualTo(1);
        assertThat(outcome.skippedExisting()).isEqualTo(1);
    }

    @Test
    @DisplayName("dedup is per source: the same news_id under another source still lands")
    void dedupScopedToSource() {
        writer().insertNew(List.of(news("300", "4866", "Barkley note")));
        entityManager.flush();

        PlayerNews otherSource = news("300", "4866", "Barkley note from elsewhere");
        otherSource.setSource("fantasypros");
        newsRepository.save(otherSource);
        entityManager.flush();
        entityManager.clear();

        assertThat(newsRepository.findAll())
                .filteredOn(row -> row.getNewsId().equals("300"))
                .hasSize(2);
    }
}
