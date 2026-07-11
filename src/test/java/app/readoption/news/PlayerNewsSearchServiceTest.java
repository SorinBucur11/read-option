package app.readoption.news;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The news layer's loud degradation vocabulary (never empty-list-silence), the
 * server-side retrieval knobs (topK + player/model filter — the model has no
 * schema field for either), and reverse-chronology with dates always present.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerNewsSearchService — degradation vocabulary, bound filter, reverse-chronology")
class PlayerNewsSearchServiceTest {

    private static final String TAG = "text-embedding-3-small";

    @Mock private VectorStore vectorStore;
    @Mock private PlayerRepository playerRepository;

    private PlayerNewsSearchService service() {
        return new PlayerNewsSearchService(vectorStore, playerRepository,
                new NewsProperties(TAG, 5));
    }

    private static Player withEspnId(String id, String espnId) {
        Player player = player(id, "Some Player", "QB", "KC", true);
        player.setEspnId(espnId);
        return player;
    }

    private static Document doc(String headline, String story, String published) {
        return Document.builder()
                .id(java.util.UUID.randomUUID().toString())
                .text(headline + "\n" + story)
                .metadata(Map.of(
                        "player_id", "4046",
                        "espn_news_id", "1",
                        "published", published,
                        "headline", headline,
                        "embedding_model", TAG))
                .build();
    }

    @Test
    @DisplayName("an unknown playerId throws - the error-tool-response self-correction path")
    void unknownPlayerThrows() {
        when(playerRepository.findById("NOPE")).thenReturn(Optional.empty());

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service().searchForPlayer("NOPE", "injury"))
                .withMessageContaining("NOPE");
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("no espn_id degrades to NEWS_UNAVAILABLE_NO_ESPN_ID - the store is never queried")
    void noEspnIdDegradesLoudly() {
        when(playerRepository.findById("13357")).thenReturn(Optional.of(withEspnId("13357", null)));

        PlayerNewsView view = service().searchForPlayer("13357", "role change");

        assertThat(view.note()).isEqualTo(NewsVocabulary.NEWS_UNAVAILABLE_NO_ESPN_ID);
        assertThat(view.items()).isNull();
        verifyNoInteractions(vectorStore);
    }

    @Test
    @DisplayName("empty retrieval degrades to NO_NEWS_FOUND, never an empty list")
    void emptyRetrievalDegradesLoudly() {
        when(playerRepository.findById("4046")).thenReturn(Optional.of(withEspnId("4046", "3139477")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        PlayerNewsView view = service().searchForPlayer("4046", "trade rumors");

        assertThat(view.note()).isEqualTo(NewsVocabulary.NO_NEWS_FOUND);
        assertThat(view.items()).isNull();
    }

    @Test
    @DisplayName("the request binds server-side topK and the player+model-generation filter")
    void requestCarriesBoundKnobs() {
        when(playerRepository.findById("4046")).thenReturn(Optional.of(withEspnId("4046", "3139477")));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        service().searchForPlayer("4046", "injury recovery status");

        ArgumentCaptor<SearchRequest> request = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(request.capture());
        assertThat(request.getValue().getQuery()).isEqualTo("injury recovery status");
        assertThat(request.getValue().getTopK()).isEqualTo(5);
        String filter = String.valueOf(request.getValue().getFilterExpression());
        assertThat(filter).contains("player_id").contains("4046")
                .contains("embedding_model").contains(TAG);
    }

    @Test
    @DisplayName("items come back reverse-chronological with the date on every item; story is "
            + "peeled from the document text")
    void itemsAreReverseChronologicalAndDated() {
        when(playerRepository.findById("4046")).thenReturn(Optional.of(withEspnId("4046", "3139477")));
        // similarity order deliberately NOT chronological
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                doc("Older report", "Old story.", "2026-03-02T10:00:00Z"),
                doc("Newest report", "New story.", "2026-06-11T15:08:41Z"),
                doc("Middle report", "Mid story.", "2026-05-03T01:48:31Z")));

        PlayerNewsView view = service().searchForPlayer("4046", "status");

        assertThat(view.note()).isNull();
        assertThat(view.items()).extracting(PlayerNewsView.NewsItem::published).containsExactly(
                "2026-06-11T15:08:41Z", "2026-05-03T01:48:31Z", "2026-03-02T10:00:00Z");
        assertThat(view.items().get(0).headline()).isEqualTo("Newest report");
        assertThat(view.items().get(0).story()).isEqualTo("New story.");
    }
}
