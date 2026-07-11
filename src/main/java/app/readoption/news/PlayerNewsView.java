package app.readoption.news;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The {@code searchPlayerNews} tool result. Degradation is loud and in the
 * RESULT, never an empty-list silence: {@code note} carries
 * {@link NewsVocabulary#NEWS_UNAVAILABLE_NO_ESPN_ID} or
 * {@link NewsVocabulary#NO_NEWS_FOUND} and everything else is omitted by
 * {@code NON_NULL} — the model reads the note and says plainly that it has no
 * news (the TeamRoomView pattern).
 *
 * <p>Items are reverse-chronological and {@code published} is always present —
 * the grounding invariant's time dimension (A-4): a news item is a point-in-time
 * report, and the date is the fact that keeps a stale truth from being cited as
 * current.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerNewsView(
        String playerId,
        String note,
        List<NewsItem> items
) {

    public static PlayerNewsView degraded(String playerId, String note) {
        return new PlayerNewsView(playerId, note, null);
    }

    /** One retrieved report: ISO-8601 publication date, headline, cleaned story text. */
    public record NewsItem(
            String published,
            String headline,
            String story
    ) {
    }
}
