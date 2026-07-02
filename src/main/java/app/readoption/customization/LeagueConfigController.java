package app.readoption.customization;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Natural-language league customization: parse → refine → confirm.
 *
 * <p><b>Stateless.</b> The partial {@link ParsedLeague} rides in the payloads — no
 * {@code ChatMemory}, no session store; the conversation state is a typed object the
 * client carries back each turn. Nothing is written before {@code /confirm}
 * ({@code READY} ≠ committed), and confirm refuses (409) while BLOCKING issues
 * remain. Hard failures surface as RFC 9457 ProblemDetail via the global handler.
 */
@RestController
@RequestMapping("/api/league")
public class LeagueConfigController {

    private final LeagueConfigService leagueConfigService;

    public LeagueConfigController(LeagueConfigService leagueConfigService) {
        this.leagueConfigService = leagueConfigService;
    }

    @PostMapping("/parse")
    public ParseResult parse(@Valid @RequestBody ParseRequest request) {
        return leagueConfigService.parse(request.description());
    }

    @PostMapping("/refine")
    public ParseResult refine(@Valid @RequestBody RefineRequest request) {
        return leagueConfigService.refine(request.current(), request.correction(), request.turn());
    }

    @PostMapping("/confirm")
    public LeagueConfig confirm(@Valid @RequestBody ConfirmRequest request) {
        return leagueConfigService.confirm(request.current());
    }
}
