package app.readoption.scoring;

/**
 * A StatLine that also knows which player it belongs to.
 *
 * StatLine is the pure scoring contract — exactly what ScoringService.calculate()
 * needs, and no more, so the unit tests can stub it with no identity. Scorable adds
 * the one thing the persistence loop needs on top: the player id, to build the
 * (player_id, year, format) key. Both PlayerStats and PlayerProjection implement it,
 * so a single scoring loop handles either source.
 */
public interface Scorable extends StatLine {

    String getPlayerId();
}