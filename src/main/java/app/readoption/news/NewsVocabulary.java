package app.readoption.news;

/**
 * The news layer's degradation vocabulary — one home, mirroring the
 * {@code TeamContextService} constants, so every news read speaks the same loud
 * strings and acceptance assertions have a single vocabulary to check. The rule
 * is never-empty-list-silence: a player outside the ESPN crosswalk and a player
 * with a genuinely empty corpus are different facts, and the agent must be able
 * to tell them apart.
 */
public final class NewsVocabulary {

    /**
     * The player has no {@code espn_id} after the 4.4 crosswalk repair — the news
     * layer cannot even ask the source about him. Distinct from an empty result:
     * this is "unqueryable", not "quiet".
     */
    public static final String NEWS_UNAVAILABLE_NO_ESPN_ID = "NEWS_UNAVAILABLE_NO_ESPN_ID";

    /** Valid, queryable player — the corpus just has nothing for him. */
    public static final String NO_NEWS_FOUND = "NO_NEWS_FOUND";

    private NewsVocabulary() {
    }
}
