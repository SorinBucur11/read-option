package app.readoption.playerprojection;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projections")
public class PlayerProjectionController {

    private final PlayerProjectionSyncService syncService;
    private final PlayerProjectionRepository playerProjectionRepository;

    public PlayerProjectionController(PlayerProjectionSyncService syncService,
                                      PlayerProjectionRepository playerProjectionRepository) {
        this.syncService = syncService;
        this.playerProjectionRepository = playerProjectionRepository;
    }

    @PostMapping("/sync/{season}")
    public String syncProjections(@PathVariable int season) {
        int count = syncService.syncProjections(season);
        return "Synced " + count + " stat lines for season " + season;
    }

    @GetMapping("/player/{playerId}")
    public List<PlayerProjection> getProjectionsByPlayer(@PathVariable String playerId) {
        return playerProjectionRepository.findByPlayerId(playerId);
    }
}
