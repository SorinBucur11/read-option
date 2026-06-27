package app.readoption.playerprojection;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projections")
public class PlayerProjectionController {

    private final PlayerProjectionSyncService syncService;
    private final EspnProjectionSyncService espnProjectionSyncService;
    private final PlayerProjectionRepository playerProjectionRepository;

    public PlayerProjectionController(PlayerProjectionSyncService syncService,
                                      EspnProjectionSyncService espnProjectionSyncService,
                                      PlayerProjectionRepository playerProjectionRepository) {
        this.syncService = syncService;
        this.espnProjectionSyncService = espnProjectionSyncService;
        this.playerProjectionRepository = playerProjectionRepository;
    }

    @PostMapping("/sync/{season}")
    public String syncProjections(@PathVariable int season) {
        int count = syncService.syncProjections(season);
        return "Synced " + count + " stat lines for season " + season;
    }

    @PostMapping("/sync/espn")
    public EspnProjectionSyncService.EspnSyncResult syncEspn() {
        return espnProjectionSyncService.sync();
    }

    @GetMapping("/player/{playerId}")
    public List<PlayerProjection> getProjectionsByPlayer(@PathVariable String playerId) {
        return playerProjectionRepository.findByPlayerId(playerId);
    }
}