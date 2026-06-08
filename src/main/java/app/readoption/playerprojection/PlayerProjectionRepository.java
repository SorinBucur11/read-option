package app.readoption.playerprojection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlayerProjectionRepository extends JpaRepository<PlayerProjection, PlayerProjectionId> {

    List<PlayerProjection> findByYear(int year);

    List<PlayerProjection> findByPlayerId(String playerId);

    @Query("SELECT DISTINCT p.year FROM PlayerProjection p")
    List<Integer> findDistinctYears();
}
