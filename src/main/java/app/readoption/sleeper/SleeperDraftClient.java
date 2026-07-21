package app.readoption.sleeper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Read-only client for Sleeper's public v1 draft endpoints ({@code RestClient}
 * idiom — the espn-package pattern, not {@code SleeperClient}'s legacy
 * {@code java.net.http}, whose migration stays deferred). No auth; Sleeper's
 * public API is unauthenticated.
 *
 * <p>Null bodies are translated to {@link IllegalStateException} here: Sleeper
 * answers unknown ids with a JSON {@code null}, and a vanished draft mid-sync is
 * an unobserved state that must halt loudly, not NPE three frames later.
 * Transport failures propagate as {@code RestClientException} — the runner
 * counts those against the error budget instead of halting.
 */
@Component
public class SleeperDraftClient {

    private static final Logger log = LoggerFactory.getLogger(SleeperDraftClient.class);

    private static final String BASE_URL = "https://api.sleeper.app/v1";

    private final RestClient restClient;

    public SleeperDraftClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(BASE_URL).build();
    }

    /** Resolves a Sleeper username to its user object (the {@code draft_order} key). */
    public SleeperUser fetchUser(String username) {
        log.debug("Fetching Sleeper user: {}", username);
        SleeperUser user = restClient.get()
                .uri("/user/{username}", username)
                .retrieve()
                .body(SleeperUser.class);
        if (user == null || user.userId() == null) {
            throw new IllegalStateException("Sleeper returned no user for username '" + username + "'");
        }
        return user;
    }

    /** Fetches one draft object by id. */
    public SleeperDraft fetchDraft(String draftId) {
        log.debug("Fetching Sleeper draft: {}", draftId);
        SleeperDraft draft = restClient.get()
                .uri("/draft/{draftId}", draftId)
                .retrieve()
                .body(SleeperDraft.class);
        if (draft == null || draft.draftId() == null) {
            throw new IllegalStateException("Sleeper returned no draft for id '" + draftId + "'");
        }
        return draft;
    }

    /** Fetches the full picks array for a draft (empty before the first pick). */
    public List<SleeperDraftPick> fetchPicks(String draftId) {
        log.debug("Fetching Sleeper draft picks: {}", draftId);
        List<SleeperDraftPick> picks = restClient.get()
                .uri("/draft/{draftId}/picks", draftId)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (picks == null) {
            throw new IllegalStateException("Sleeper returned no picks array for draft '" + draftId + "'");
        }
        return picks;
    }
}
