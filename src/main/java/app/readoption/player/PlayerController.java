package app.readoption.player;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/players")
public class PlayerController {

    private final PlayerSyncService syncService;
    private final PlayerRepository playerRepository;

    public PlayerController(PlayerSyncService syncService, PlayerRepository playerRepository) {
        this.syncService = syncService;
        this.playerRepository = playerRepository;
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
}
