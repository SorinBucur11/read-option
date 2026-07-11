package app.readoption.news;

import app.readoption.espn.EspnNewsClient;
import app.readoption.espn.EspnNewsResponse;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Syncs every ESPN-resolvable board-scope player's news feed into the
 * {@code player_news} landing table. READ (fetch, filter, map — no transaction)
 * then WRITE per player via {@link PlayerNewsWriter}; one player's failure never
 * aborts the run, and a skip never deletes — rolled-off source items are
 * unrecoverable (audit A-3), so the landing table only ever grows.
 *
 * <p>Scope is the board's: QB/RB/WR/TE (K/DST excluded to match
 * {@code DraftBoardService}), {@code espn_id} present. Deliberately wider than
 * the projected pool — camp-season news for a player who gains a projection
 * later would otherwise be permanently lost. Sequential fetches; the
 * reconciliation concurrency item stays deferred.
 *
 * <p>Only {@code type == "Rotowire"} items land (D0): the feed multiplexes
 * player blurbs with editorial {@code Story} items, filtered at the write
 * boundary — the seasonType==2 pattern.
 */
@Service
public class PlayerNewsSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerNewsSyncService.class);

    static final String ROTOWIRE_TYPE = "Rotowire";
    private static final List<String> BOARD_POSITIONS = List.of("QB", "RB", "WR", "TE");

    private final EspnNewsClient newsClient;
    private final PlayerRepository playerRepository;
    private final PlayerNewsMapper mapper;
    private final PlayerNewsWriter writer;

    public PlayerNewsSyncService(EspnNewsClient newsClient,
                                 PlayerRepository playerRepository,
                                 PlayerNewsMapper mapper,
                                 PlayerNewsWriter writer) {
        this.newsClient = newsClient;
        this.playerRepository = playerRepository;
        this.mapper = mapper;
        this.writer = writer;
    }

    public NewsSyncReport sync() {
        List<Player> players = playerRepository.findByEspnIdIsNotNullAndPositionIn(BOARD_POSITIONS);

        int playersSynced = 0;
        int itemsInserted = 0;
        int itemsSkippedExisting = 0;
        List<String> failed = new ArrayList<>();

        for (Player player : players) {
            try {
                long espnPlayerId = Long.parseLong(player.getEspnId().trim());
                EspnNewsResponse feed = newsClient.fetchPlayerNews(espnPlayerId);
                List<PlayerNews> rows = feed.feed().stream()
                        .filter(item -> ROTOWIRE_TYPE.equals(item.type()))
                        .map(item -> mapper.toEntity(player.getId(), espnPlayerId, item))
                        .toList();
                PlayerNewsWriter.InsertOutcome outcome = writer.insertNew(rows);
                playersSynced++;
                itemsInserted += outcome.inserted();
                itemsSkippedExisting += outcome.skippedExisting();
            } catch (Exception e) {
                log.warn("News sync failed for {} ({}): {}",
                        player.getFullName(), player.getId(), e.getMessage());
                failed.add(player.getId() + " " + player.getFullName()
                        + " (" + e.getMessage() + ")");
            }
        }

        log.info("News sync: {} players synced, {} items inserted, {} skipped existing, {} failed",
                playersSynced, itemsInserted, itemsSkippedExisting, failed.size());
        return new NewsSyncReport(playersSynced, itemsInserted, itemsSkippedExisting, failed);
    }

    /** Three-outcome sync report plus the WARN list of players whose fetch failed. */
    public record NewsSyncReport(
            int playersSynced,
            int itemsInserted,
            int itemsSkippedExisting,
            List<String> failed) {
    }
}
