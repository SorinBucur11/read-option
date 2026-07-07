package app.readoption.team;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The per-team WRITE phase of the schedule sync, in its own bean so the
 * {@code @Transactional} proxy applies (the orchestrator loops HTTP fetches and
 * must never hold a transaction across them). One transaction per team: a failed
 * team rolls back atomically — its old rows survive, and a rebuilt bye is never
 * left pointing at half-landed weeks.
 */
@Component
public class TeamScheduleWriter {

    private final TeamScheduleRepository scheduleRepository;
    private final NflTeamRepository nflTeamRepository;

    public TeamScheduleWriter(TeamScheduleRepository scheduleRepository,
                              NflTeamRepository nflTeamRepository) {
        this.scheduleRepository = scheduleRepository;
        this.nflTeamRepository = nflTeamRepository;
    }

    /**
     * Delete-and-reload per {@code (team, season)} — the derived-shape rebuild rule,
     * same posture as {@code player_scoring} — plus the derived bye in the same
     * transaction ({@code byeWeek} null when not derivable; never a guess).
     */
    @Transactional
    public void replace(String team, int season, List<TeamSchedule> rows, Integer byeWeek) {
        scheduleRepository.deleteByTeamAndSeason(team, season);
        scheduleRepository.saveAll(rows);

        NflTeam nflTeam = nflTeamRepository.findById(team)
                .orElseThrow(() -> new IllegalStateException("nfl_team row missing for " + team));
        nflTeam.setByeWeek(byeWeek);   // managed entity — dirty checking flushes it at commit
    }
}
