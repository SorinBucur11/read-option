package app.readoption.draft;

import app.readoption.sleeper.SleeperDraftClient;
import app.readoption.sleeper.SleeperSyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sync-loop registry: one virtual thread per watched draft, polling
 * {@link DraftSyncService#pollOnce} on the configured cadence until a terminal
 * status ends the loop. In-memory only — same non-problem as in-memory
 * ChatMemory: one user, one sitting; a crash loses only the loop, and restart +
 * set-difference recovers.
 *
 * <p>Failure dispatch is type-based: {@link IllegalStateException} is a
 * validation-gate halt (immediate ERROR, no retry); anything else is a poll
 * failure counted against the consecutive-failure error budget, reset on
 * success.
 */
@Component
public class DraftSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(DraftSyncRunner.class);

    private final DraftSyncService syncService;
    private final SleeperDraftClient client;
    private final SleeperSyncProperties properties;

    /** Terminal entries are kept for status queries until a new start replaces them. */
    private final Map<String, SyncHandle> registry = new ConcurrentHashMap<>();

    public DraftSyncRunner(DraftSyncService syncService,
                           SleeperDraftClient client,
                           SleeperSyncProperties properties) {
        this.syncService = syncService;
        this.client = client;
        this.properties = properties;
    }

    /**
     * Starts a sync loop for a draft. Restarting a draft whose session already
     * exists (crash recovery, relink) and mid-draft linking are both legitimate —
     * the set-difference catches up. Only a still-running loop for the same
     * draftId is a conflict.
     */
    public DraftSyncStatus.Report start(String draftId, long leagueConfigId) {
        SyncHandle existing = registry.get(draftId);
        if (existing != null && !existing.status.isTerminal()) {
            throw new DraftSyncConflictException("a sync is already running for draft " + draftId);
        }
        String username = properties.username();
        if (username == null || username.isBlank()) {
            throw new InvalidDraftRequestException(
                    "readoption.sleeper.username is not set — cannot start a Sleeper sync");
        }
        // Resolve the user once (cached in the handle for the sync's lifetime) and
        // validate the draft exists before any loop runs.
        String userId;
        try {
            userId = client.fetchUser(username).userId();
            client.fetchDraft(draftId);
        } catch (IllegalStateException | RestClientException e) {
            throw new InvalidDraftRequestException("cannot start Sleeper sync: " + e.getMessage());
        }

        SyncHandle handle = new SyncHandle(draftId);
        registry.put(draftId, handle);
        handle.thread = Thread.ofVirtual()
                .name("sleeper-sync-" + draftId)
                .unstarted(() -> runLoop(handle, leagueConfigId, userId));
        handle.thread.start();
        log.info("started Sleeper sync for draft {} (leagueConfigId={}, user={})",
                draftId, leagueConfigId, username);
        return handle.report();
    }

    /** Status report for a known draftId; 404 otherwise. */
    public DraftSyncStatus.Report status(String draftId) {
        return requireHandle(draftId).report();
    }

    /**
     * Cooperative stop: sets the flag, interrupts the sleep, and waits briefly so
     * the returned report is usually final. A loop already terminal returns its
     * report unchanged.
     */
    public DraftSyncStatus.Report stop(String draftId) {
        SyncHandle handle = requireHandle(draftId);
        if (!handle.status.isTerminal()) {
            handle.stopRequested = true;
            Thread thread = handle.thread;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(Duration.ofSeconds(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return handle.report();
    }

    private void runLoop(SyncHandle handle, long leagueConfigId, String userId) {
        int consecutiveFailures = 0;
        int consecutiveShortfalls = 0;
        while (!handle.stopRequested) {
            try {
                DraftSyncService.PollReport report =
                        syncService.pollOnce(handle.draftId, leagueConfigId, userId);
                handle.lastPollAt = Instant.now();
                handle.sessionId = report.sessionId();
                handle.picksSynced = report.totalPicks();
                handle.error = null;
                consecutiveFailures = 0;
                // Completion-shortfall grace: a separate counter from the failure
                // budget — a transport blip must not reset the settle clock, and a
                // short poll is a successful poll, not a failure.
                if (report.shortfall() > 0) {
                    consecutiveShortfalls++;
                    if (consecutiveShortfalls >= properties.sync().completionGracePolls()) {
                        handle.status = DraftSyncStatus.ERROR;
                        handle.error = "draft complete at Sleeper but picks settled at "
                                + report.totalPicks() + "/" + (report.totalPicks() + report.shortfall())
                                + " after " + consecutiveShortfalls + " grace polls — relink to retry";
                        log.error("Sleeper sync halted for draft {}: {}", handle.draftId, handle.error);
                        return;
                    }
                } else {
                    consecutiveShortfalls = 0;
                }
                handle.status = report.status();
                if (report.status() == DraftSyncStatus.COMPLETE) {
                    log.info("Sleeper sync complete for draft {}: {} picks", handle.draftId,
                            report.totalPicks());
                    return;
                }
            } catch (IllegalStateException halt) {
                // validation gate: retrying a 3RR draft five times cannot make it snake
                handle.lastPollAt = Instant.now();
                handle.error = halt.getMessage();
                handle.status = DraftSyncStatus.ERROR;
                log.error("Sleeper sync halted for draft {}: {}", handle.draftId, halt.getMessage());
                return;
            } catch (Exception e) {
                consecutiveFailures++;
                handle.lastPollAt = Instant.now();
                handle.error = e.getMessage();
                log.warn("Sleeper sync poll failure {}/{} for draft {}: {}", consecutiveFailures,
                        properties.sync().errorBudget(), handle.draftId, e.getMessage());
                if (consecutiveFailures >= properties.sync().errorBudget()) {
                    handle.status = DraftSyncStatus.ERROR;
                    log.error("Sleeper sync error budget exhausted for draft {} — halting",
                            handle.draftId, e);
                    return;
                }
            }
            try {
                Thread.sleep(properties.sync().pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // stopRequested ends the loop
            }
        }
        handle.status = DraftSyncStatus.STOPPED;
        log.info("Sleeper sync stopped for draft {}", handle.draftId);
    }

    private SyncHandle requireHandle(String draftId) {
        SyncHandle handle = registry.get(draftId);
        if (handle == null) {
            throw new DraftSyncNotFoundException(draftId);
        }
        return handle;
    }

    /** Mutable per-sync state; volatile fields because loop and HTTP threads both touch them. */
    private static final class SyncHandle {

        private final String draftId;
        private volatile DraftSyncStatus status = DraftSyncStatus.WATCHING;
        private volatile Long sessionId;
        private volatile int picksSynced;
        private volatile Instant lastPollAt;
        private volatile String error;
        private volatile boolean stopRequested;
        private volatile Thread thread;

        private SyncHandle(String draftId) {
            this.draftId = draftId;
        }

        private DraftSyncStatus.Report report() {
            return new DraftSyncStatus.Report(draftId, status, sessionId, picksSynced, lastPollAt, error);
        }
    }
}
