package app.readoption.playerprojection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface PlayerProjectionRawRepository
        extends JpaRepository<PlayerProjectionRaw, PlayerProjectionRawId> {

    // existing keys for this load, so Persistable upsert does UPDATE not INSERT
    @Query("SELECT r.playerId FROM PlayerProjectionRaw r WHERE r.year = :year AND r.source = :source")
    Set<String> findPlayerIdsByYearAndSource(@Param("year") int year, @Param("source") String source);

    // all source rows for a season — the reconciliation READ phase groups these by player
    List<PlayerProjectionRaw> findByYear(int year);
}