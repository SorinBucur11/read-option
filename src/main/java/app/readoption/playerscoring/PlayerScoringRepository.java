package app.readoption.playerscoring;

import app.readoption.scoring.ScoringFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PlayerScoringRepository extends JpaRepository<PlayerScoring, PlayerScoringId> {

    List<PlayerScoring> findByPlayerId(String playerId);

    List<PlayerScoring> findByPlayerIdAndYear(String playerId, int year);

    List<PlayerScoring> findByYear(int year);

    List<PlayerScoring> findByYearAndScoringFormat(int year, ScoringFormat scoringFormat);

    @Query(value = """
        SELECT new app.readoption.playerscoring.LeaderboardRow(
            p.id, p.fullName, p.position, p.team,
            s.totalPoints, s.pointsPerGame, s.gamesPlayed)
        FROM PlayerScoring s
        JOIN Player p ON p.id = s.playerId
        WHERE s.year = :season
          AND s.scoringFormat = :format
          AND (:position IS NULL OR p.position = :position)
          AND (:active IS NULL OR p.active = :active)
        ORDER BY s.totalPoints DESC
        """,
            countQuery = """
        SELECT COUNT(s)
        FROM PlayerScoring s
        JOIN Player p ON p.id = s.playerId
        WHERE s.year = :season
          AND s.scoringFormat = :format
          AND (:position IS NULL OR p.position = :position)
          AND (:active IS NULL OR p.active = :active)
        """)
    Page<LeaderboardRow> findLeaderboard(
            @Param("season") int season,
            @Param("format") ScoringFormat format,
            @Param("position") String position,
            @Param("active") Boolean active,
            Pageable pageable);

    List<PlayerScoring> findByPlayerIdAndScoringFormatOrderByYearAsc(
            String playerId, ScoringFormat format);

    @Query(nativeQuery = true, value = """
        WITH ranked AS (
            SELECT s.player_id,
                   p.full_name,
                   p.position,
                   p.team,
                   p.active,
                   s.total_points,
                   s.points_per_game,
                   s.games_played,
                   CASE :adpBucket
                       WHEN 'STANDARD' THEN pr.adp_std
                       WHEN 'HALF_PPR' THEN pr.adp_half_ppr
                       WHEN 'PPR'      THEN pr.adp_ppr
                   END AS adp
            FROM player_scoring s
            JOIN player p ON p.id = s.player_id
            LEFT JOIN player_projections pr
                   ON pr.player_id = s.player_id
                  AND pr.year = s.year
            WHERE s.year = :season
              AND s.scoring_format = :format
        ),
        overlay AS (
            SELECT player_id,
                   full_name,
                   position,
                   team,
                   active,
                   total_points,
                   points_per_game,
                   games_played,
                   adp,
                   RANK() OVER (PARTITION BY position ORDER BY total_points DESC) AS value_rank_position,
                   RANK() OVER (                      ORDER BY total_points DESC) AS value_rank_overall,
                   CASE WHEN adp IS NOT NULL
                        THEN RANK() OVER (PARTITION BY position ORDER BY adp ASC NULLS LAST)
                        END AS market_rank_position,
                   CASE WHEN adp IS NOT NULL
                        THEN RANK() OVER (                      ORDER BY adp ASC NULLS LAST)
                        END AS market_rank_overall
            FROM ranked
        )
        SELECT player_id            AS player_id,
               full_name            AS full_name,
               position             AS position,
               team                 AS team,
               total_points         AS total_points,
               points_per_game      AS points_per_game,
               games_played         AS games_played,
               adp                  AS adp,
               value_rank_position  AS value_rank_position,
               value_rank_overall   AS value_rank_overall,
               market_rank_position AS market_rank_position,
               market_rank_overall  AS market_rank_overall
        FROM overlay
        WHERE (:position IS NULL OR position = :position)
          AND (:active   IS NULL OR active   = :active)
        ORDER BY total_points DESC
        """,
            countQuery = """
        SELECT COUNT(*)
        FROM player_scoring s
        JOIN player p ON p.id = s.player_id
        WHERE s.year = :season
          AND s.scoring_format = :format
          AND (:position IS NULL OR p.position = :position)
          AND (:active   IS NULL OR p.active   = :active)
        """)
    Page<RankedLeaderboardRow> findRankedLeaderboard(
            @Param("season") int season,
            @Param("format") String format,
            @Param("adpBucket") String adpBucket,
            @Param("position") String position,
            @Param("active") Boolean active,
            Pageable pageable);
}
