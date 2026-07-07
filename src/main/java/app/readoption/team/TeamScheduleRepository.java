package app.readoption.team;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TeamScheduleRepository extends JpaRepository<TeamSchedule, TeamScheduleId> {

    List<TeamSchedule> findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc(
            String team, int season, int week);

    /**
     * Bulk JPQL delete, deliberately not a derived {@code deleteBy}: the reload
     * inserts rows with the same composite PKs, and Hibernate's flush order runs
     * INSERTs before entity DELETEs — a queued delete would collide with its own
     * replacement. The bulk statement executes immediately instead;
     * flush/clearAutomatically keep the persistence context honest, since a bulk
     * statement bypasses it and stale managed rows would collide with the reload.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TeamSchedule ts WHERE ts.team = :team AND ts.season = :season")
    void deleteByTeamAndSeason(@Param("team") String team, @Param("season") int season);
}
