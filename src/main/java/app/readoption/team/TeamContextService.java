package app.readoption.team;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single home of player→team context reads and their degradation vocabulary.
 * Everywhere player.team is joined the posture is LEFT JOIN + loud degradation:
 * a null team (2,199 active free agents) or an unknown team (stale {@code OAK}
 * rows) yields an explicit "unavailable" string in the view — never a dropped
 * row, never a silent remap of OAK→LV. Both the profile and the draft-state
 * roster speak these exact strings, so the agent's acceptance assertions have
 * one vocabulary to check.
 */
@Service
public class TeamContextService {

    public static final String NO_TEAM = "free agent / no team";
    public static final String BYE_UNKNOWN_NO_TEAM = "bye unknown - team context unavailable";
    public static final String BYE_UNKNOWN = "bye unknown";
    public static final String OPPONENTS_UNAVAILABLE = "unknown - team context unavailable";
    public static final String OPPONENTS_NOT_SYNCED = "unknown - schedule not synced";

    /**
     * Deliberately fixed at weeks 1–3: the draft is pre-season, so "next opponents"
     * and "first opponents" coincide. In-season "next from current week" is Phase 5.
     */
    private static final int EARLY_WEEKS = 3;

    private final NflTeamRepository nflTeamRepository;
    private final TeamScheduleRepository scheduleRepository;

    public TeamContextService(NflTeamRepository nflTeamRepository,
                              TeamScheduleRepository scheduleRepository) {
        this.nflTeamRepository = nflTeamRepository;
        this.scheduleRepository = scheduleRepository;
    }

    /** Bye + weeks 1–3 opponents for one player's team (profile enrichment). */
    public TeamContext contextFor(String team, int season) {
        if (team == null) {
            return new TeamContext(BYE_UNKNOWN_NO_TEAM, List.of(OPPONENTS_UNAVAILABLE));
        }
        NflTeam nflTeam = nflTeamRepository.findById(team).orElse(null);
        if (nflTeam == null) {
            // e.g. stale OAK: degrade loudly, never remap.
            return new TeamContext(BYE_UNKNOWN_NO_TEAM, List.of(OPPONENTS_UNAVAILABLE));
        }

        String byeWeek = nflTeam.getByeWeek() != null
                ? String.valueOf(nflTeam.getByeWeek())
                : BYE_UNKNOWN;

        List<TeamSchedule> earlyWeeks = scheduleRepository
                .findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc(team, season, EARLY_WEEKS);
        List<String> earlyOpponents = earlyWeeks.isEmpty()
                ? List.of(OPPONENTS_NOT_SYNCED)
                : earlyWeeks.stream().map(TeamContextService::formatOpponent).toList();

        return new TeamContext(byeWeek, earlyOpponents);
    }

    /**
     * Batch bye labels for the given (non-null) team abbrevs — one query, not one
     * per roster entry. Unknown abbrevs degrade to {@link #BYE_UNKNOWN_NO_TEAM};
     * callers map a null team to the same string themselves.
     */
    public Map<String, String> byeWeekLabels(Collection<String> teams) {
        Map<String, NflTeam> found = nflTeamRepository.findAllById(teams).stream()
                .collect(Collectors.toMap(NflTeam::getAbbrev, Function.identity()));
        return teams.stream().distinct().collect(Collectors.toMap(
                Function.identity(),
                team -> {
                    NflTeam nflTeam = found.get(team);
                    if (nflTeam == null) {
                        return BYE_UNKNOWN_NO_TEAM;
                    }
                    return nflTeam.getByeWeek() != null
                            ? String.valueOf(nflTeam.getByeWeek())
                            : BYE_UNKNOWN;
                }));
    }

    private static String formatOpponent(TeamSchedule game) {
        return "W" + game.getWeek()
                + (game.isHome() ? " vs " : " at ")
                + game.getOpponent()
                + (game.isHome() ? " (home)" : " (away)");
    }

    /** One team's draft-relevant schedule facts, already degraded to loud strings. */
    public record TeamContext(String byeWeek, List<String> earlyOpponents) {
    }
}
