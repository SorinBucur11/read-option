package app.readoption.playerscoring;

import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
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
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, size);
        String positionName = position != null ? position.name() : null;

        return playerScoringRepository.findLeaderboard(season, format, positionName, active, pageable);
    }

    @GetMapping("/leaderboard/ranked")
    public Page<RankedLeaderboardRow> rankedLeaderboard(
            @RequestParam(defaultValue = "${readoption.current-season}") int season,
            @RequestParam(defaultValue = "STANDARD_6PT") ScoringFormat format,
            @RequestParam(required = false) Position position,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(MAX_PAGE_SIZE) int size) {

        Pageable pageable = PageRequest.of(page, size);
        String positionName = position != null ? position.name() : null;

        return playerScoringRepository.findRankedLeaderboard(
                season,
                format.name(),
                format.adpBucket().name(),
                positionName,
                active,
                pageable);
    }
}
