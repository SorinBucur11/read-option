package app.readoption.news;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-player WRITE phase of the news sync, in its own bean so the
 * {@code @Transactional} proxy applies (the orchestrator loops HTTP fetches and
 * must never hold a transaction across them — the TeamScheduleWriter lesson).
 *
 * <p>Landing semantics are <b>insert-only</b>: dedup is an existence check on
 * {@code (source, news_id)}; an already-landed row is never updated — the landing
 * table is the permanent record of what the source said when we first saw it.
 */
@Component
public class PlayerNewsWriter {

    private final PlayerNewsRepository newsRepository;

    public PlayerNewsWriter(PlayerNewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    /**
     * One transaction per player: inserts the rows not yet landed, counts the
     * rest as skipped. Within-batch duplicates collapse first (last write wins
     * before the DB ever sees them) so a doubled feed item cannot poison the flush.
     */
    @Transactional
    public InsertOutcome insertNew(List<PlayerNews> rows) {
        if (rows.isEmpty()) {
            return new InsertOutcome(0, 0);
        }
        Map<String, PlayerNews> byNewsId = new LinkedHashMap<>();
        rows.forEach(row -> byNewsId.put(row.getNewsId(), row));

        Set<String> existing = Set.copyOf(newsRepository.findExistingNewsIds(
                PlayerNewsMapper.SOURCE, byNewsId.keySet()));
        List<PlayerNews> fresh = byNewsId.values().stream()
                .filter(row -> !existing.contains(row.getNewsId()))
                .toList();

        newsRepository.saveAll(fresh);
        return new InsertOutcome(fresh.size(), rows.size() - fresh.size());
    }

    public record InsertOutcome(int inserted, int skippedExisting) {
    }
}
