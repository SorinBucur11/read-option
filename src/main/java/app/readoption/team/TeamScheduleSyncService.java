package app.readoption.team;

import app.readoption.espn.EspnScheduleClient;
import app.readoption.espn.EspnScheduleResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Syncs every team's regular-season schedule from ESPN's site API and derives
 * each bye from the schedule gap. READ (fetch, map, derive — no transaction) then
 * WRITE per team via {@link TeamScheduleWriter}; one team's failure never blocks
 * the other 31, and a vocabulary surprise (unknown ESPN abbreviation) throws
 * before anything is deleted — dirty rows must never land.
 */
@Service
public class TeamScheduleSyncService {

    private static final Logger log = LoggerFactory.getLogger(TeamScheduleSyncService.class);

    /** Regular season; type 1 is preseason, whose week numbers collide with the PK. */
    private static final int REGULAR_SEASON_TYPE = 2;
    private static final int REGULAR_SEASON_GAMES = 17;
    private static final int LAST_WEEK = 18;

    private final EspnScheduleClient scheduleClient;
    private final NflTeamRepository nflTeamRepository;
    private final TeamScheduleWriter writer;

    public TeamScheduleSyncService(EspnScheduleClient scheduleClient,
                                   NflTeamRepository nflTeamRepository,
                                   TeamScheduleWriter writer) {
        this.scheduleClient = scheduleClient;
        this.nflTeamRepository = nflTeamRepository;
        this.writer = writer;
    }

    public TeamScheduleSyncReport sync(int season) {
        List<NflTeam> teams = nflTeamRepository.findAll();
        // Crosswalk loaded once, not per row: ESPN vocabulary -> Sleeper vocabulary.
        Map<String, String> espnToSleeper = teams.stream()
                .collect(Collectors.toMap(NflTeam::getEspnAbbrev, NflTeam::getAbbrev));

        List<String> synced = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (NflTeam team : teams) {
            try {
                EspnScheduleResponse response =
                        scheduleClient.fetchSchedule(team.getEspnAbbrev(), season);
                List<TeamSchedule> rows = toRows(team, season, response, espnToSleeper);
                if (rows.isEmpty()) {
                    // Season not published yet (or nothing regular-season): existing
                    // rows survive — a skip must never wipe last season's context.
                    skipped.add(team.getAbbrev() + " (no regular-season events)");
                    continue;
                }
                Integer byeWeek = deriveByeWeek(team.getAbbrev(), rows);
                writer.replace(team.getAbbrev(), season, rows, byeWeek);
                synced.add(team.getAbbrev() + " (" + rows.size() + " weeks, bye "
                        + (byeWeek == null ? "unknown" : byeWeek) + ")");
            } catch (Exception e) {
                log.error("Schedule sync failed for {}: {}", team.getAbbrev(), e.getMessage());
                failed.add(team.getAbbrev() + " (" + e.getMessage() + ")");
            }
        }

        log.info("Schedule sync season {}: {} synced, {} skipped, {} failed",
                season, synced.size(), skipped.size(), failed.size());
        return new TeamScheduleSyncReport(season, teams.size(), synced, skipped, failed);
    }

    private List<TeamSchedule> toRows(NflTeam team, int season,
                                      EspnScheduleResponse response,
                                      Map<String, String> espnToSleeper) {
        List<TeamSchedule> rows = new ArrayList<>();
        for (EspnScheduleResponse.Event event : response.events()) {
            if (event.seasonType() == null || event.seasonType().type() == null
                    || event.seasonType().type() != REGULAR_SEASON_TYPE) {
                continue;
            }
            if (event.week() == null || event.week().number() == null) {
                throw new IllegalStateException(
                        "malformed ESPN event for " + team.getAbbrev() + ": no week number");
            }
            int week = event.week().number();

            EspnScheduleResponse.Competitor ours = null;
            EspnScheduleResponse.Competitor opponent = null;
            for (EspnScheduleResponse.Competitor competitor : competitors(event, team, week)) {
                if (competitor.team() != null
                        && team.getEspnAbbrev().equals(competitor.team().abbreviation())) {
                    ours = competitor;
                } else {
                    opponent = competitor;
                }
            }
            if (ours == null || opponent == null || opponent.team() == null) {
                throw new IllegalStateException("could not identify competitors for "
                        + team.getAbbrev() + " week " + week);
            }

            String opponentAbbrev = espnToSleeper.get(opponent.team().abbreviation());
            if (opponentAbbrev == null) {
                // Vocabulary surprise: fail this team's sync loudly, land nothing.
                throw new IllegalStateException("unknown ESPN abbreviation '"
                        + opponent.team().abbreviation() + "' for " + team.getAbbrev()
                        + " week " + week);
            }

            rows.add(TeamSchedule.builder()
                    .team(team.getAbbrev())
                    .season(season)
                    .week(week)
                    .opponent(opponentAbbrev)
                    .isHome("home".equalsIgnoreCase(ours.homeAway()))
                    .build());
        }
        return rows;
    }

    private List<EspnScheduleResponse.Competitor> competitors(
            EspnScheduleResponse.Event event, NflTeam team, int week) {
        if (event.competitions() == null || event.competitions().isEmpty()
                || event.competitions().get(0).competitors() == null) {
            throw new IllegalStateException("malformed ESPN event for "
                    + team.getAbbrev() + " week " + week + ": no competitors");
        }
        return event.competitions().get(0).competitors();
    }

    /**
     * The bye is the schedule gap — but only when the landed shape proves it:
     * exactly 17 rows with exactly one week in 1..18 missing. Anything else
     * (partial fetch, duplicate weeks) derives NULL and warns; an absent bye is
     * recoverable, a wrong one poisons every roster-bye read.
     */
    private Integer deriveByeWeek(String team, List<TeamSchedule> rows) {
        Set<Integer> landedWeeks = rows.stream()
                .map(TeamSchedule::getWeek)
                .collect(Collectors.toSet());
        List<Integer> missing = IntStream.rangeClosed(1, LAST_WEEK)
                .filter(week -> !landedWeeks.contains(week))
                .boxed()
                .toList();

        if (rows.size() == REGULAR_SEASON_GAMES && missing.size() == 1) {
            return missing.get(0);
        }
        log.warn("Bye not derivable for {}: {} rows landed, missing weeks {}",
                team, rows.size(), missing);
        return null;
    }

    /** Three-outcome sync report: per-team success / skipped / failed. */
    public record TeamScheduleSyncReport(
            int season,
            int teams,
            List<String> synced,
            List<String> skipped,
            List<String> failed) {}
}
