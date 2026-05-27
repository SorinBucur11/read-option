package app.readoption.playerscoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerScoringRepository extends JpaRepository<PlayerScoring, PlayerScoringId> {

    List<PlayerScoring> findByPlayerId(String playerId);

    List<PlayerScoring> findByPlayerIdAndYear(String playerId, int year);

    List<PlayerScoring> findByYear(int year);

    List<PlayerScoring> findByYearAndScoringFormat(int year, String scoringFormat);

}
