package app.readoption.espn;

public class EspnUnavailableException extends RuntimeException {
    public EspnUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}