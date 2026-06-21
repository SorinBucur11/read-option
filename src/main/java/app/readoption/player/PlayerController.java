package app.readoption.player;

import app.readoption.scoring.ScoringFormat;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerSyncService syncService;
    private final PlayerRepository playerRepository;
    private final PlayerProfileService playerProfileService;

    public PlayerController(PlayerSyncService syncService,
                            PlayerRepository playerRepository,
                            PlayerProfileService playerProfileService) {
        this.syncService = syncService;
        this.playerRepository = playerRepository;
        this.playerProfileService = playerProfileService;
    }

    @PostMapping("/sync")
    public String syncPlayers() {
        int count = syncService.syncPlayers();
        return "Synced " + count + " players";
    }

    @GetMapping
    public List<Player> getAllPlayers() {
        return playerRepository.findAll();
    }

    @GetMapping("/position/{position}")
    public List<Player> getByPosition(@PathVariable String position) {
        return playerRepository.findByPosition(position);
    }

    @GetMapping("/{id}/profile")
    public PlayerProfile profile(
            @PathVariable String id,
            @RequestParam(defaultValue = "STANDARD_6PT") ScoringFormat format) {
        return playerProfileService.getProfile(id, format);
    }
}
