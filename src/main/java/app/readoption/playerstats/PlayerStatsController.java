package app.readoption.playerstats;

import app.readoption.player.Player;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stats")
public class PlayerStatsController {

    private final PlayerStatsSyncService syncService;
    private final PlayerStatsRepository playerStatsRepository;

    public PlayerStatsController(PlayerStatsSyncService syncService,
                                 PlayerStatsRepository playerStatsRepository) {
        this.syncService = syncService;
        this.playerStatsRepository = playerStatsRepository;
    }

    @PostMapping("/sync/{season}")
    public String syncStats(@PathVariable int season) {
        int count = syncService.syncStats(season);
        return "Synced " + count + " stat lines for season " + season;
    }

    @GetMapping("/player/{playerId}")
    public List<PlayerStats> getStatsByPlayer(@PathVariable String playerId) {
        return playerStatsRepository.findByPlayerId(playerId);
    }

    @GetMapping("/season/{year}")
    public List<PlayerStats> getStatsBySeason(@PathVariable int year) {
        return playerStatsRepository.findByYear(year);
    }

    @GetMapping("/player/{playerId}/season/{year}")
    public List<PlayerStats> getStatsByPlayerAndSeason(@PathVariable String playerId,
                                                       @PathVariable int year) {
        return playerStatsRepository.findByPlayerIdAndYear(playerId, year);
    }
}
