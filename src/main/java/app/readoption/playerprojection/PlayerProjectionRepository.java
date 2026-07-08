package app.readoption.playerprojection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlayerProjectionRepository extends JpaRepository<PlayerProjection, PlayerProjectionId> {

    List<PlayerProjection> findByYear(int year);

    List<PlayerProjection> findByPlayerId(String playerId);

    @Query("SELECT DISTINCT p.year FROM PlayerProjection p")
    List<Integer> findDistinctYears();

    Optional<PlayerProjection> findByPlayerIdAndYear(String playerId, Integer year);

    /**
     * Projection-existence check for tool candidate lists — ids only (the mart row
     * is wide), one query for the whole batch.
     */
    @Query("SELECT p.playerId FROM PlayerProjection p WHERE p.year = :year AND p.playerId IN :playerIds")
    List<String> findPlayerIdsWithProjection(@Param("year") int year,
                                             @Param("playerIds") Collection<String> playerIds);

    @Query("""
        SELECT count(pr)
        FROM PlayerProjection pr
        JOIN Player pl ON pl.id = pr.playerId
        WHERE pr.year = :season
          AND pl.position = :position
          AND (CASE
                 WHEN :bucket = 'STANDARD' THEN pr.adpStd
                 WHEN :bucket = 'HALF_PPR' THEN pr.adpHalfPpr
                 ELSE pr.adpPpr
               END) < :playerAdp
        """)
    long countBetterAdpAtPosition(@Param("season") Integer season,
                                  @Param("position") String position,
                                  @Param("bucket") String bucket,
                                  @Param("playerAdp") BigDecimal playerAdp);
}
