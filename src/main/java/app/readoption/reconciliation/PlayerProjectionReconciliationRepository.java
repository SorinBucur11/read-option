package app.readoption.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface PlayerProjectionReconciliationRepository
        extends JpaRepository<PlayerProjectionReconciliation, PlayerProjectionReconciliationId> {

    List<PlayerProjectionReconciliation> findByYear(int year);

    // existing keys for this season, so Persistable upsert does UPDATE not INSERT
    @Query("SELECT a.playerId FROM PlayerProjectionReconciliation a WHERE a.year = :year")
    Set<String> findPlayerIdsByYear(@Param("year") int year);
}
