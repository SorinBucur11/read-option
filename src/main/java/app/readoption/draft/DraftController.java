package app.readoption.draft;

import app.readoption.scoring.Position;
import app.readoption.valuation.DraftBoardService;
import app.readoption.valuation.DraftBoardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Draft sessions, picks, state, and the VORP board. The server assigns every pick
 * number; hard failures surface as RFC 9457 ProblemDetail via the global handler.
 */
@Validated
@RestController
@RequestMapping("/api/draft")
public class DraftController {

    private final DraftService draftService;
    private final DraftBoardService draftBoardService;

    public DraftController(DraftService draftService, DraftBoardService draftBoardService) {
        this.draftService = draftService;
        this.draftBoardService = draftBoardService;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftSession startSession(@Valid @RequestBody StartDraftRequest request) {
        return draftService.startSession(request);
    }

    @PostMapping("/sessions/{id}/picks")
    @ResponseStatus(HttpStatus.CREATED)
    public DraftPickView recordPick(@PathVariable long id,
                                    @Valid @RequestBody RecordPickRequest request) {
        return draftService.recordPick(id, request);
    }

    @GetMapping("/sessions/{id}/state")
    public DraftStateView getState(@PathVariable long id) {
        return draftService.getState(id);
    }

    @GetMapping("/sessions/{id}/board")
    public DraftBoardView getBoard(@PathVariable long id,
                                   @RequestParam(required = false) Position position,
                                   @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
        return draftBoardService.getBoard(id, position, limit);
    }
}
