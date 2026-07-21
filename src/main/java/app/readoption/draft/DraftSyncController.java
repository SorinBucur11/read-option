package app.readoption.draft;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sleeper live-draft sync: link, status, stop. Linking is 202 — the loop runs in
 * the background and the session appears on the first {@code drafting}
 * observation. Hard failures surface as RFC 9457 ProblemDetail via the global
 * handler.
 */
@Validated
@RestController
@RequestMapping("/api/sleeper/sync")
public class DraftSyncController {

    private final DraftSyncRunner runner;

    public DraftSyncController(DraftSyncRunner runner) {
        this.runner = runner;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DraftSyncStatus.Report start(@Valid @RequestBody StartSyncRequest request) {
        return runner.start(request.draftId(), request.leagueConfigId());
    }

    @GetMapping("/{draftId}")
    public DraftSyncStatus.Report status(@PathVariable String draftId) {
        return runner.status(draftId);
    }

    @PostMapping("/{draftId}/stop")
    public DraftSyncStatus.Report stop(@PathVariable String draftId) {
        return runner.stop(draftId);
    }

    public record StartSyncRequest(
            @NotBlank String draftId,
            @NotNull Long leagueConfigId
    ) {}
}
