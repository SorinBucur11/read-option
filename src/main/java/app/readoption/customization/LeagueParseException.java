package app.readoption.customization;

/**
 * The model's output could not be turned into a {@link ParsedLeague} — transport
 * failure, malformed JSON, or an out-of-enum value. There is <b>no silent default</b>
 * for league config (the deliberate divergence from Phase 2's {@code TRUST_CONSENSUS}
 * fallback): the caller surfaces this as a BLOCKING parse-failure issue and the user
 * must re-state their league.
 */
public class LeagueParseException extends RuntimeException {

    public LeagueParseException(String message) {
        super(message);
    }

    public LeagueParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
