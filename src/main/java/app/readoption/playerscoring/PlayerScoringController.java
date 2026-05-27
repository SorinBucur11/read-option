package app.readoption.playerscoring;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scoring")
public class PlayerScoringController {

    private final PlayerScoringService playerScoringService;
    private final PlayerScoringRepository playerScoringRepository;

    public PlayerScoringController(PlayerScoringService playerScoringService,
                                   PlayerScoringRepository playerScoringRepository) {
        this.playerScoringService = playerScoringService;
        this.playerScoringRepository = playerScoringRepository;
    }

    @PostMapping("/compute/{season}")
    public ResponseEntity<String> computeForSeason(@PathVariable int season) {
        int rows = playerScoringService.computeAndSaveForSeason(season);
        return ResponseEntity.ok("Computed " + rows + " scoring rows for season " + season);
    }

    @PostMapping("/recompute")
    public ResponseEntity<String> recomputeAll() {
        int rows = playerScoringService.recomputeAllSeasons();
        return ResponseEntity.ok("Recomputed " + rows + " total scoring rows");
    }

    @GetMapping("/player/{playerId}")
    public List<PlayerScoring> getByPlayer(@PathVariable String playerId) {
        return playerScoringRepository.findByPlayerId(playerId);
    }

    @GetMapping("/player/{playerId}/season/{year}")
    public List<PlayerScoring> getByPlayerAndSeason(@PathVariable String playerId,
                                                    @PathVariable int year) {
        return playerScoringRepository.findByPlayerIdAndYear(playerId, year);
    }
}
