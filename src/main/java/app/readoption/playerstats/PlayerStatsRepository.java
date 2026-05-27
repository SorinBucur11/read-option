package app.readoption.playerstats;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlayerStatsRepository extends JpaRepository<PlayerStats, PlayerStatsId> {

    List<PlayerStats> findByPlayerId(String playerId);

    List<PlayerStats> findByYear(int year);

    List<PlayerStats> findByPlayerIdAndYear(String playerId, int year);

    @Query("SELECT DISTINCT ps.year FROM PlayerStats ps ORDER BY ps.year")
    List<Integer> findDistinctYears();

}