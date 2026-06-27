package app.readoption.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, String> {

    List<Player> findByPosition(String position);

    List<Player> findByTeam(String team);

    List<Player> findByActiveTrue();

    List<Player> findByPositionAndActiveTrue(String position);

    @Query("SELECT p.id FROM Player p")
    List<String> findAllIds();

    @Modifying
    @Query("""
       UPDATE Player p
          SET p.espnId = :espnId, p.updatedAt = CURRENT_TIMESTAMP
        WHERE p.id = :playerId
          AND (p.espnId IS NULL OR p.espnId <> :espnId)
       """)
    int updateEspnId(@Param("playerId") String playerId, @Param("espnId") String espnId);

    Optional<Player> findByEspnId(String espnId);
}