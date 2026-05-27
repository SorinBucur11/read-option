package app.readoption.playerscoring;

import java.io.Serializable;
import java.util.Objects;

public class PlayerScoringId implements Serializable {

    private String playerId;
    private int year;
    private String scoringFormat;

    public PlayerScoringId() {
    }

    public PlayerScoringId(String playerId, int year, String scoringFormat) {
        this.playerId = playerId;
        this.year = year;
        this.scoringFormat = scoringFormat;
    }

    public String getPlayerId() {
        return playerId;
    }

    public int getYear() {
        return year;
    }

    public String getScoringFormat() {
        return scoringFormat;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerScoringId that = (PlayerScoringId) o;
        return year == that.year
                && Objects.equals(playerId, that.playerId)
                && Objects.equals(scoringFormat, that.scoringFormat);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, year, scoringFormat);
    }
}
