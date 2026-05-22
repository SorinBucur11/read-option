package app.readoption.playerstats;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerStatsRepository extends JpaRepository<PlayerStats, PlayerStatsId> {

    List<PlayerStats> findByPlayerId(String playerId);

    List<PlayerStats> findByYear(int year);
}