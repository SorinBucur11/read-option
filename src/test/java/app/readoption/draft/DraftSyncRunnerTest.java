package app.readoption.draft;

import app.readoption.sleeper.SleeperDraft;
import app.readoption.sleeper.SleeperDraftClient;
import app.readoption.sleeper.SleeperSyncProperties;
import app.readoption.sleeper.SleeperUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runner lifecycle over a mocked {@link DraftSyncService}: error budget counts
 * consecutive failures and resets on success, gate halts are immediate ERROR,
 * stop is cooperative, duplicate starts conflict. Poll interval is 5ms so the
 * loop turns fast; a small await helper replaces a dependency on Awaitility
 * (not on the classpath).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftSyncRunner — loop lifecycle, error budget, stop, registry")
class DraftSyncRunnerTest {

    private static final String DRAFT_ID = "1382999990000000001";
    private static final String USER_ID = "87732859926102016";
    private static final long CONFIG_ID = 42L;

    @Mock private DraftSyncService syncService;
    @Mock private SleeperDraftClient client;

    private DraftSyncRunner runner(String username, int errorBudget) {
        return runner(username, errorBudget, 20);
    }

    private DraftSyncRunner runner(String username, int errorBudget, int completionGracePolls) {
        return new DraftSyncRunner(syncService, client, new SleeperSyncProperties(
                username, new SleeperSyncProperties.Sync(Duration.ofMillis(5), errorBudget,
                        completionGracePolls)));
    }

    private void stubStartValidation() {
        when(client.fetchUser("sosososik")).thenReturn(new SleeperUser(USER_ID, "sosososik"));
        when(client.fetchDraft(DRAFT_ID)).thenReturn(new SleeperDraft(DRAFT_ID, "pre_draft",
                "snake", "2026", new SleeperDraft.Settings(10, 15, 120, null), null));
    }

    private static DraftSyncStatus.Report await(DraftSyncRunner runner, String draftId,
                                                Predicate<DraftSyncStatus.Report> condition) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            DraftSyncStatus.Report report = runner.status(draftId);
            if (condition.test(report)) {
                return report;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return fail("condition not met within 2s; last report: " + runner.status(draftId));
    }

    @Test
    @DisplayName("start without username -> loud 400, no loop")
    void startWithoutUsername() {
        assertThatExceptionOfType(InvalidDraftRequestException.class)
                .isThrownBy(() -> runner("", 5).start(DRAFT_ID, CONFIG_ID))
                .withMessageContaining("readoption.sleeper.username");
    }

    @Test
    @DisplayName("start with unresolvable draft -> loud 400, no loop")
    void startWithUnknownDraft() {
        when(client.fetchUser("sosososik")).thenReturn(new SleeperUser(USER_ID, "sosososik"));
        when(client.fetchDraft(DRAFT_ID))
                .thenThrow(new IllegalStateException("Sleeper returned no draft for id '" + DRAFT_ID + "'"));

        assertThatExceptionOfType(InvalidDraftRequestException.class)
                .isThrownBy(() -> runner("sosososik", 5).start(DRAFT_ID, CONFIG_ID))
                .withMessageContaining("no draft");
    }

    @Test
    @DisplayName("start with Sleeper unreachable -> loud 400, no loop")
    void startWithTransportFailure() {
        when(client.fetchUser("sosososik")).thenThrow(new RestClientException("connection refused"));

        assertThatExceptionOfType(InvalidDraftRequestException.class)
                .isThrownBy(() -> runner("sosososik", 5).start(DRAFT_ID, CONFIG_ID))
                .withMessageContaining("connection refused");
    }

    @Test
    @DisplayName("error budget: consecutive poll failures -> ERROR with the last exception")
    void errorBudgetExhausted() {
        stubStartValidation();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID)))
                .thenThrow(new RestClientException("Sleeper 503"));
        DraftSyncRunner runner = runner("sosososik", 3);

        runner.start(DRAFT_ID, CONFIG_ID);
        DraftSyncStatus.Report report = await(runner, DRAFT_ID, r -> r.state().isTerminal());

        assertThat(report.state()).isEqualTo(DraftSyncStatus.ERROR);
        assertThat(report.error()).contains("Sleeper 503");
        verify(syncService, times(3)).pollOnce(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a success resets the failure counter — 2-failure streaks never trip a budget of 3")
    void successResetsErrorBudget() throws InterruptedException {
        stubStartValidation();
        AtomicInteger polls = new AtomicInteger();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID))).thenAnswer(inv -> {
            if (polls.incrementAndGet() % 3 != 0) {
                throw new RestClientException("flaky");
            }
            return new DraftSyncService.PollReport(DraftSyncStatus.WATCHING, null, 0, 0, 0);
        });
        DraftSyncRunner runner = runner("sosososik", 3);

        runner.start(DRAFT_ID, CONFIG_ID);
        await(runner, DRAFT_ID, r -> polls.get() >= 12);   // 8 failures total, never 3 in a row

        assertThat(runner.status(DRAFT_ID).state()).isNotEqualTo(DraftSyncStatus.ERROR);
        DraftSyncStatus.Report stopped = runner.stop(DRAFT_ID);
        assertThat(stopped.state()).isEqualTo(DraftSyncStatus.STOPPED);
    }

    @Test
    @DisplayName("validation-gate halt (IllegalStateException) -> immediate ERROR, no retry")
    void gateHaltIsImmediateError() {
        stubStartValidation();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID)))
                .thenThrow(new IllegalStateException("3RR drafts unsupported — draft x has reversal_round=3"));
        DraftSyncRunner runner = runner("sosososik", 5);

        runner.start(DRAFT_ID, CONFIG_ID);
        DraftSyncStatus.Report report = await(runner, DRAFT_ID, r -> r.state().isTerminal());

        assertThat(report.state()).isEqualTo(DraftSyncStatus.ERROR);
        assertThat(report.error()).contains("3RR");
        verify(syncService, times(1)).pollOnce(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("COMPLETE poll ends the loop and the report carries session + pick count")
    void completeEndsLoop() {
        stubStartValidation();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID)))
                .thenReturn(new DraftSyncService.PollReport(DraftSyncStatus.COMPLETE, 99L, 0, 150, 0));
        DraftSyncRunner runner = runner("sosososik", 5);

        runner.start(DRAFT_ID, CONFIG_ID);
        DraftSyncStatus.Report report = await(runner, DRAFT_ID, r -> r.state().isTerminal());

        assertThat(report.state()).isEqualTo(DraftSyncStatus.COMPLETE);
        assertThat(report.sessionId()).isEqualTo(99L);
        assertThat(report.picksSynced()).isEqualTo(150);
    }

    // ----- completion-shortfall grace (5.0-b) -----

    @Test
    @DisplayName("shortfall streak reaches the grace budget -> ERROR naming both counts, loop stopped")
    void completionGraceExhausted() {
        stubStartValidation();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID)))
                .thenReturn(new DraftSyncService.PollReport(DraftSyncStatus.SYNCING, 99L, 0, 166, 2));
        DraftSyncRunner runner = runner("sosososik", 5, 3);

        runner.start(DRAFT_ID, CONFIG_ID);
        DraftSyncStatus.Report report = await(runner, DRAFT_ID, r -> r.state().isTerminal());

        assertThat(report.state()).isEqualTo(DraftSyncStatus.ERROR);
        assertThat(report.error()).contains("166/168").contains("relink");
        verify(syncService, times(3)).pollOnce(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("shortfall streak broken by the array settling -> COMPLETE, no ERROR")
    void shortfallSettlesWithinGrace() {
        stubStartValidation();
        AtomicInteger polls = new AtomicInteger();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID))).thenAnswer(inv ->
                polls.incrementAndGet() < 3
                        ? new DraftSyncService.PollReport(DraftSyncStatus.SYNCING, 99L, 0, 166, 2)
                        : new DraftSyncService.PollReport(DraftSyncStatus.COMPLETE, 99L, 2, 168, 0));
        DraftSyncRunner runner = runner("sosososik", 5, 3);

        runner.start(DRAFT_ID, CONFIG_ID);
        DraftSyncStatus.Report report = await(runner, DRAFT_ID, r -> r.state().isTerminal());

        assertThat(report.state()).isEqualTo(DraftSyncStatus.COMPLETE);
        assertThat(report.error()).isNull();
        assertThat(report.picksSynced()).isEqualTo(168);
    }

    @Test
    @DisplayName("grace counter independent of the failure budget: alternating failure/shortfall trips neither prematurely")
    void graceIndependentOfFailureBudget() {
        stubStartValidation();
        AtomicInteger polls = new AtomicInteger();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID))).thenAnswer(inv -> {
            if (polls.incrementAndGet() % 2 == 1) {
                throw new RestClientException("Sleeper 503");
            }
            return new DraftSyncService.PollReport(DraftSyncStatus.SYNCING, 99L, 0, 166, 2);
        });
        // 8 alternating polls: failures never consecutive (budget 3 untouched),
        // shortfalls reach 4 (grace 10 untouched) — a failure must NOT reset the
        // shortfall streak, but neither counter may trip here.
        DraftSyncRunner runner = runner("sosososik", 3, 10);

        runner.start(DRAFT_ID, CONFIG_ID);
        await(runner, DRAFT_ID, r -> polls.get() >= 8);

        assertThat(runner.status(DRAFT_ID).state()).isNotEqualTo(DraftSyncStatus.ERROR);
        DraftSyncStatus.Report stopped = runner.stop(DRAFT_ID);
        assertThat(stopped.state()).isEqualTo(DraftSyncStatus.STOPPED);
    }

    @Test
    @DisplayName("stop -> cooperative STOPPED; duplicate start conflicts while running, allowed after")
    void stopAndRestart() {
        stubStartValidation();
        when(syncService.pollOnce(eq(DRAFT_ID), eq(CONFIG_ID), eq(USER_ID)))
                .thenReturn(new DraftSyncService.PollReport(DraftSyncStatus.WATCHING, null, 0, 0, 0));
        DraftSyncRunner runner = runner("sosososik", 5);

        runner.start(DRAFT_ID, CONFIG_ID);
        assertThatExceptionOfType(DraftSyncConflictException.class)
                .isThrownBy(() -> runner.start(DRAFT_ID, CONFIG_ID))
                .withMessageContaining(DRAFT_ID);

        DraftSyncStatus.Report stopped = runner.stop(DRAFT_ID);
        assertThat(stopped.state()).isEqualTo(DraftSyncStatus.STOPPED);

        // terminal entry is replaceable: restart after stop is legitimate (relink/recovery)
        DraftSyncStatus.Report restarted = runner.start(DRAFT_ID, CONFIG_ID);
        assertThat(restarted.state()).isEqualTo(DraftSyncStatus.WATCHING);
        runner.stop(DRAFT_ID);
    }

    @Test
    @DisplayName("status/stop on an unknown draftId -> 404")
    void unknownDraftId() {
        DraftSyncRunner runner = runner("sosososik", 5);

        assertThatExceptionOfType(DraftSyncNotFoundException.class)
                .isThrownBy(() -> runner.status("nope"));
        assertThatExceptionOfType(DraftSyncNotFoundException.class)
                .isThrownBy(() -> runner.stop("nope"));
    }
}
