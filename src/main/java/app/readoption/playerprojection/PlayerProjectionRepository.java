package app.readoption.playerprojection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PlayerProjectionRepository extends JpaRepository<PlayerProjection, PlayerProjectionId> {

    List<PlayerProjection> findByYear(int year);

    List<PlayerProjection> findByPlayerId(String playerId);
}
