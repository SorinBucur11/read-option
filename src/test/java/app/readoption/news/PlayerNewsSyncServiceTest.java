package app.readoption.news;

import app.readoption.espn.EspnNewsClient;
import app.readoption.espn.EspnNewsResponse;
import app.readoption.espn.EspnUnavailableException;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The write-boundary filter (only {@code Rotowire} items land), the
 * skip-never-aborts failure posture, and the three-outcome report arithmetic.
 * The mapper is real (it is part of the boundary under test); the writer and
 * client are mocked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerNewsSyncService — Rotowire filter, per-player failure isolation, report arithmetic")
class PlayerNewsSyncServiceTest {

    @Mock private EspnNewsClient newsClient;
    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerNewsWriter writer;

    private PlayerNewsSyncService service() {
        return new PlayerNewsSyncService(newsClient, playerRepository,
                new PlayerNewsMapper(new JsonMapper()), writer);
    }

    private static Player espnPlayer(String id, String name, String espnId) {
        Player player = player(id, name, "QB", "KC", true);
        player.setEspnId(espnId);
        return player;
    }

    private static EspnNewsResponse.Item item(long id, String type, String headline) {
        return new EspnNewsResponse.Item(id, type, headline, "desc", "<p>story</p>",
                "2026-06-11T15:08:41Z", "2026-06-11T15:08:41Z", false, 3139477L);
    }

    @Test
    @DisplayName("only Rotowire items land: Story items are dropped at the write boundary")
    void rotowireFilterApplied() {
        when(playerRepository.findByEspnIdIsNotNullAndPositionIn(anyCollection()))
                .thenReturn(List.of(espnPlayer("4046", "Patrick Mahomes", "3139477")));
        when(newsClient.fetchPlayerNews(3139477L)).thenReturn(new EspnNewsResponse(List.of(
                item(1L, "Rotowire", "Mahomes cleared"),
                item(2L, "Story", "Sleepers and busts for 2026"),
                item(3L, "Rotowire", "Mahomes limited in OTAs"))));
        when(writer.insertNew(anyList())).thenReturn(new PlayerNewsWriter.InsertOutcome(2, 0));

        PlayerNewsSyncService.NewsSyncReport report = service().sync();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlayerNews>> landed = ArgumentCaptor.forClass(List.class);
        verify(writer).insertNew(landed.capture());
        assertThat(landed.getValue())
                .extracting(PlayerNews::getNewsId)
                .containsExactly("1", "3");
        assertThat(landed.getValue()).allSatisfy(row -> {
            assertThat(row.getSource()).isEqualTo("espn");
            assertThat(row.getPlayerId()).isEqualTo("4046");
            assertThat(row.getEspnPlayerId()).isEqualTo(3139477L);
            assertThat(row.getPublished()).isNotNull();
            assertThat(row.getSourcePayload()).contains("\"headline\"");
        });
        assertThat(report.playersSynced()).isEqualTo(1);
        assertThat(report.itemsInserted()).isEqualTo(2);
    }

    @Test
    @DisplayName("one player's fetch failure is WARNed and skipped - the run continues")
    void failedPlayerSkippedRunContinues() {
        when(playerRepository.findByEspnIdIsNotNullAndPositionIn(anyCollection()))
                .thenReturn(List.of(
                        espnPlayer("4046", "Patrick Mahomes", "3139477"),
                        espnPlayer("4866", "Saquon Barkley", "3929630")));
        when(newsClient.fetchPlayerNews(3139477L))
                .thenThrow(new EspnUnavailableException("ESPN news source is unavailable", null));
        when(newsClient.fetchPlayerNews(3929630L)).thenReturn(new EspnNewsResponse(List.of(
                item(9L, "Rotowire", "Barkley rests"))));
        when(writer.insertNew(anyList())).thenReturn(new PlayerNewsWriter.InsertOutcome(1, 0));

        PlayerNewsSyncService.NewsSyncReport report = service().sync();

        assertThat(report.playersSynced()).isEqualTo(1);
        assertThat(report.itemsInserted()).isEqualTo(1);
        assertThat(report.failed()).hasSize(1);
        assertThat(report.failed().get(0)).contains("4046").contains("Patrick Mahomes");
    }

    @Test
    @DisplayName("a non-numeric espn_id fails that player loudly, never a half-mapped row")
    void malformedEspnIdFailsThatPlayer() {
        when(playerRepository.findByEspnIdIsNotNullAndPositionIn(anyCollection()))
                .thenReturn(List.of(espnPlayer("X1", "Corrupt Crosswalk", "not-a-number")));

        PlayerNewsSyncService.NewsSyncReport report = service().sync();

        assertThat(report.playersSynced()).isZero();
        assertThat(report.failed()).hasSize(1);
        verify(writer, never()).insertNew(anyList());
    }

    @Test
    @DisplayName("three-outcome arithmetic: inserted and skipped-existing accumulate across players")
    void reportArithmeticAccumulates() {
        when(playerRepository.findByEspnIdIsNotNullAndPositionIn(anyCollection()))
                .thenReturn(List.of(
                        espnPlayer("4046", "Patrick Mahomes", "3139477"),
                        espnPlayer("4866", "Saquon Barkley", "3929630")));
        when(newsClient.fetchPlayerNews(3139477L)).thenReturn(new EspnNewsResponse(List.of(
                item(1L, "Rotowire", "A"), item(2L, "Rotowire", "B"))));
        when(newsClient.fetchPlayerNews(3929630L)).thenReturn(new EspnNewsResponse(List.of(
                item(3L, "Rotowire", "C"))));
        when(writer.insertNew(anyList()))
                .thenReturn(new PlayerNewsWriter.InsertOutcome(1, 1))
                .thenReturn(new PlayerNewsWriter.InsertOutcome(0, 1));

        PlayerNewsSyncService.NewsSyncReport report = service().sync();

        assertThat(report.playersSynced()).isEqualTo(2);
        assertThat(report.itemsInserted()).isEqualTo(1);
        assertThat(report.itemsSkippedExisting()).isEqualTo(2);
        assertThat(report.failed()).isEmpty();
    }

    @Test
    @DisplayName("an empty feed still counts the player as synced - quiet is not failure")
    void emptyFeedCountsAsSynced() {
        when(playerRepository.findByEspnIdIsNotNullAndPositionIn(anyCollection()))
                .thenReturn(List.of(espnPlayer("4046", "Patrick Mahomes", "3139477")));
        when(newsClient.fetchPlayerNews(3139477L)).thenReturn(new EspnNewsResponse(List.of()));
        when(writer.insertNew(anyList())).thenReturn(new PlayerNewsWriter.InsertOutcome(0, 0));

        PlayerNewsSyncService.NewsSyncReport report = service().sync();

        assertThat(report.playersSynced()).isEqualTo(1);
        assertThat(report.itemsInserted()).isZero();
        assertThat(report.failed()).isEmpty();
    }
}
