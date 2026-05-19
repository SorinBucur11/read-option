package app.readoption.player;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PlayerRepository extends JpaRepository<Player, String> {

    List<Player> findByPosition(String position);

    List<Player> findByTeam(String team);

    List<Player> findByActiveTrue();

    List<Player> findByPositionAndActiveTrue(String position);
}