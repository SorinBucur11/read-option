package app.readoption.customization;

public class LeagueConfigNotFoundException extends RuntimeException {

    public LeagueConfigNotFoundException(long leagueConfigId) {
        super("No league config found with id: " + leagueConfigId);
    }
}
