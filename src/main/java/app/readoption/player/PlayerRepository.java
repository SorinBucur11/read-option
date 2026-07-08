package app.readoption.player;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, String> {

    List<Player> findByPosition(String position);

    /**
     * The players ahead on a depth-chart ladder — scoped to the RAW sub-position
     * (an order-2 SWR sees only the order-1 SWR, never the LWR/RWR starters:
     * normalized-WR scoping would report false competition). In SQL by design,
     * not a Java filter over a team fetch.
     */
    List<Player> findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
            String team, String depthChartPosition, Integer depthChartOrder);

    /**
     * A team's depth-chart room across one or more raw ladders (e.g. WR spans
     * LWR/RWR/SWR), ordered ladder-then-order — the get_team_context read.
     */
    List<Player> findByTeamAndDepthChartPositionInOrderByDepthChartPositionAscDepthChartOrderAsc(
            String team, Collection<String> depthChartPositions);

    List<Player> findByTeam(String team);

    /**
     * The find_player search: partial, case-insensitive, active only, capped at 5.
     * Name order makes ambiguous results deterministic for the model to pick from.
     */
    List<Player> findTop5ByActiveTrueAndFullNameContainingIgnoreCaseOrderByFullNameAsc(String name);

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