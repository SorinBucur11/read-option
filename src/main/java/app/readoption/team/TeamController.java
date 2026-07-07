package app.readoption.team;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamScheduleSyncService syncService;
    private final int currentSeason;

    public TeamController(TeamScheduleSyncService syncService,
                          @Value("${readoption.current-season}") int currentSeason) {
        this.syncService = syncService;
        this.currentSeason = currentSeason;
    }

    @PostMapping("/schedule/sync")
    public TeamScheduleSyncService.TeamScheduleSyncReport syncSchedule(
            @RequestParam(required = false) Integer season) {
        return syncService.sync(season != null ? season : currentSeason);
    }
}
