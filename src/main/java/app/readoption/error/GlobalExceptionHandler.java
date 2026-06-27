package app.readoption.error;

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