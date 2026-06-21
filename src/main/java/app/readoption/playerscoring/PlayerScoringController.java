package app.readoption.playerscoring;

import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringFormat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scoring")
public class PlayerScoringController {

    private final PlayerScoringService playerScoringService;
    private final PlayerScoringRepository playerScoringRepository;

    private static final int MAX_PAGE_SIZE = 100;

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

    @GetMapping("/leaderboard")
    public Page<LeaderboardRow> leaderboard(
            @RequestParam(defaultValue = "${readoption.current-season}") int season,
            @RequestParam(defaultValue = "STANDARD_6PT") ScoringFormat format,
            @RequestParam(required = false) Position position,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        int safeSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(page, safeSize);
        String positionName = (position != null) ? position.name() : null;
        return playerScoringRepository.findLeaderboard(season, format, positionName, pageable);
    }
}
