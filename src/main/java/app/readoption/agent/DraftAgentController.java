package app.readoption.agent;

import app.readoption.agent.dto.AdviceRequest;
import app.readoption.agent.dto.AdviceResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One conversational turn with the draft advisor. Hard failures surface as
 * RFC 9457 ProblemDetail via the global handler: 404 unknown session, 400 blank
 * message, 500 loop-limit.
 */
@RestController
@RequestMapping("/api/draft")
public class DraftAgentController {

    private final DraftAgentService draftAgentService;

    public DraftAgentController(DraftAgentService draftAgentService) {
        this.draftAgentService = draftAgentService;
    }

    @PostMapping("/sessions/{id}/advise")
    public AdviceResponse advise(@PathVariable long id, @RequestBody AdviceRequest request) {
        return draftAgentService.advise(id, request.message());
    }
}
