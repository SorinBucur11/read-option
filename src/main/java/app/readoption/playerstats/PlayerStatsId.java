package app.readoption.playerstats;

import java.io.Serializable;
import java.util.Objects;

public class PlayerStatsId implements Serializable {

    private String playerId;
    private int year;

    public PlayerStatsId() {}

    public PlayerStatsId(String playerId, int year) {
        this.playerId = playerId;
        this.year = year;
    }

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerStatsId that = (PlayerStatsId) o;
        return year == that.year && Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, year);
    }
}