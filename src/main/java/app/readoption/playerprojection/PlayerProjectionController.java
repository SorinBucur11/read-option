package app.readoption.playerprojection;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projections")
public class PlayerProjectionController {

    private final RotowireProjectionSyncService rotowireSyncService;
    private final EspnProjectionSyncService espnProjectionSyncService;
    private final PlayerProjectionRepository playerProjectionRepository;

    public PlayerProjectionController(RotowireProjectionSyncService rotowireSyncService,
                                      EspnProjectionSyncService espnProjectionSyncService,
                                      PlayerProjectionRepository playerProjectionRepository) {
        this.rotowireSyncService = rotowireSyncService;
        this.espnProjectionSyncService = espnProjectionSyncService;
        this.playerProjectionRepository = playerProjectionRepository;
    }

    // rotowire now lands in player_projection_raw (source='rotowire'), not the mart.
    // After Phase 2 the only writer of player_projections is the reconciliation step.
    @PostMapping("/sync/{season}")
    public String syncRotowire(@PathVariable int season) {
        int count = rotowireSyncService.sync(season);
        return "Landed " + count + " rotowire raw stat lines for season " + season;
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