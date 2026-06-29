package app.readoption.playerstats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface PlayerStatsRepository extends JpaRepository<PlayerStats, PlayerStatsId> {

    List<PlayerStats> findByPlayerId(String playerId);

    List<PlayerStats> findByYear(int year);

    List<PlayerStats> findByPlayerIdAndYear(String playerId, int year);

    /**
     * Batch fetch of actuals for a set of players across a set of seasons — one query,
     * never N+1. Backs {@code PriorSeasonContextRetriever}'s one-shot retrieval over all
     * contested-eligible players during the reconciliation READ phase.
     */
    List<PlayerStats> findByPlayerIdInAndYearIn(Collection<String> playerIds, Collection<Integer> years);

    @Query("SELECT DISTINCT ps.year FROM PlayerStats ps ORDER BY ps.year")
    List<Integer> findDistinctYears();

}