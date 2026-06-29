package app.readoption.reconciliation;

import java.io.Serializable;
import java.util.Objects;

/** Composite key for {@link PlayerProjectionReconciliation}: one audit row per player per season. */
public class PlayerProjectionReconciliationId implements Serializable {

    private String playerId;
    private int year;

    public PlayerProjectionReconciliationId() {
    }

    public PlayerProjectionReconciliationId(String playerId, int year) {
        this.playerId = playerId;
        this.year = year;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PlayerProjectionReconciliationId that)) return false;
        return year == that.year && Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerId, year);
    }
}
