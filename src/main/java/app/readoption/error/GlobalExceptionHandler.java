package app.readoption.error;

import app.readoption.agent.AgentLoopLimitException;
import app.readoption.customization.LeagueConfigNotFoundException;
import app.readoption.customization.LeagueConfigNotReadyException;
import app.readoption.draft.DraftSessionNotActiveException;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.InvalidDraftRequestException;
import app.readoption.draft.PlayerAlreadyDraftedException;
import app.readoption.espn.EspnUnavailableException;
import app.readoption.player.PlayerNotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PlayerNotFoundException.class)
    public ProblemDetail handlePlayerNotFound(PlayerNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Player Not Found");
        pd.setType(URI.create("https://readoption.app/problems/player-not-found"));
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        this::leafFieldName,
                        ConstraintViolation::getMessage,
                        (first, second) -> first));
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more request parameters are invalid");
        pd.setTitle("Validation Failed");
        pd.setType(URI.create("https://readoption.app/problems/validation"));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = "Parameter '" + ex.getName() + "' has an invalid value: '" + ex.getValue() + "'";
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        pd.setTitle("Invalid Parameter");
        pd.setType(URI.create("https://readoption.app/problems/invalid-parameter"));
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle("Internal Server Error");
        pd.setType(URI.create("https://readoption.app/problems/internal-error"));
        return pd;
    }

    @ExceptionHandler(LeagueConfigNotReadyException.class)
    public ProblemDetail handleLeagueConfigNotReady(LeagueConfigNotReadyException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "League config has blocking issues and cannot be confirmed");
        pd.setTitle("League Config Not Ready");
        pd.setType(URI.create("https://readoption.app/problems/league-config-not-ready"));
        pd.setProperty("issues", ex.getIssues());
        return pd;
    }

    @ExceptionHandler(DraftSessionNotFoundException.class)
    public ProblemDetail handleDraftSessionNotFound(DraftSessionNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Draft Session Not Found");
        pd.setType(URI.create("https://readoption.app/problems/draft-session-not-found"));
        return pd;
    }

    @ExceptionHandler(DraftSessionNotActiveException.class)
    public ProblemDetail handleDraftSessionNotActive(DraftSessionNotActiveException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Draft Session Not Active");
        pd.setType(URI.create("https://readoption.app/problems/draft-session-not-active"));
        pd.setProperty("status", ex.getStatus().name());
        return pd;
    }

    @ExceptionHandler(PlayerAlreadyDraftedException.class)
    public ProblemDetail handlePlayerAlreadyDrafted(PlayerAlreadyDraftedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Player Already Drafted");
        pd.setType(URI.create("https://readoption.app/problems/player-already-drafted"));
        pd.setProperty("playerId", ex.getPlayerId());
        if (ex.getOverallPickNo() != null) {
            pd.setProperty("overallPickNo", ex.getOverallPickNo());
        }
        return pd;
    }

    @ExceptionHandler(LeagueConfigNotFoundException.class)
    public ProblemDetail handleLeagueConfigNotFound(LeagueConfigNotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("League Config Not Found");
        pd.setType(URI.create("https://readoption.app/problems/league-config-not-found"));
        return pd;
    }

    @ExceptionHandler(InvalidDraftRequestException.class)
    public ProblemDetail handleInvalidDraftRequest(InvalidDraftRequestException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        pd.setTitle("Invalid Draft Request");
        pd.setType(URI.create("https://readoption.app/problems/invalid-draft-request"));
        return pd;
    }

    @ExceptionHandler(AgentLoopLimitException.class)
    public ProblemDetail handleAgentLoopLimit(AgentLoopLimitException ex) {
        log.error("Draft agent loop limit exceeded", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Draft advice could not be completed within the configured tool-iteration cap");
        pd.setTitle("Agent Loop Limit Exceeded");
        pd.setType(URI.create("https://readoption.app/problems/agent-loop-limit"));
        return pd;
    }

    @ExceptionHandler(EspnUnavailableException.class)
    public ProblemDetail handleEspnUnavailable(EspnUnavailableException ex) {
        log.error("Upstream ESPN failure", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, ex.getMessage());
        pd.setTitle("Upstream Provider Unavailable");
        pd.setType(URI.create("https://readoption.app/problems/upstream-unavailable"));
        return pd;
    }

    private String leafFieldName(ConstraintViolation<?> v) {
        String path = v.getPropertyPath().toString(); // e.g. "leaderboard.size"
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}