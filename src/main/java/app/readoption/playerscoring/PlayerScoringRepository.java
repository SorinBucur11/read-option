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
        ORDER BY s.totalPoints DESC
        """,
            countQuery = """
        SELECT count(s)
        FROM PlayerScoring s
        JOIN Player p ON p.id = s.playerId
        WHERE s.year = :season
          AND s.scoringFormat = :format
          AND (:position IS NULL OR p.position = :position)
        """)
    Page<LeaderboardRow> findLeaderboard(@Param("season") Integer season,
                                         @Param("format") ScoringFormat format,
                                         @Param("position") String position,
                                         Pageable pageable);

    List<PlayerScoring> findByPlayerIdAndScoringFormatOrderByYearAsc(
            String playerId, ScoringFormat format);
}
