package app.readoption.player;

import org.springframework.stereotype.Service;

@Service
public class PlayerDataSyncService {

    private final PlayerSyncService playerSyncService;
    private final PlayerIdMappingService playerIdMappingService;

    public PlayerDataSyncService(PlayerSyncService playerSyncService,
                                 PlayerIdMappingService playerIdMappingService) {
        this.playerSyncService = playerSyncService;
        this.playerIdMappingService = playerIdMappingService;
    }

    /**
     * Full player-data sync as two independent transactions:
     * stage 1 fetches players from Sleeper, stage 2 enriches espn_id from the
     * player id map. Intentionally NOT @Transactional — stage 1 stays committed
     * even if stage 2 fails on a bad mapping file.
     */
    public PlayerDataSyncResult syncAll() {
        int playersSynced = playerSyncService.syncPlayers();
        var mapping = playerIdMappingService.syncEspnIds();
        return new PlayerDataSyncResult(playersSynced, mapping);
    }

    public record PlayerDataSyncResult(
            int playersSynced,
            PlayerIdMappingService.PlayerIdMappingResult mapping) {}
}