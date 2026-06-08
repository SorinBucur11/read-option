package app.readoption.playerprojection;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperProjection;
import app.readoption.sleeper.SleeperProjectionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlayerProjectionSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerProjectionSyncService.class);

    private final SleeperClient sleeperClient;
    private final PlayerProjectionRepository playerProjectionRepository;
    private final PlayerRepository playerRepository;
    private final PlayerScoringService playerScoringService;

    public PlayerProjectionSyncService(SleeperClient sleeperClient,
                                       PlayerProjectionRepository playerProjectionRepository,
                                       PlayerRepository playerRepository,
                                       PlayerScoringService playerScoringService) {
        this.sleeperClient = sleeperClient;
        this.playerProjectionRepository = playerProjectionRepository;
        this.playerRepository = playerRepository;
        this.playerScoringService = playerScoringService;
    }

    @Transactional
    public int syncProjections(int season) {
        log.info("Starting projections sync for season {}", season);

        List<SleeperProjection> allProjections = sleeperClient.fetchProjections(season);

        Set<String> knownPlayerIds = playerRepository.findAll().stream()
                .map(Player::getId)
                .collect(Collectors.toSet());

        List<SleeperProjection> relevantProjections = allProjections.stream()
                .filter(p -> p.playerId() != null)
                .filter(p -> p.stats() != null)
                .filter(p -> knownPlayerIds.contains(p.playerId()))
                .toList();

        log.info("Filtered to {} relevant projections (from {} total)",
                relevantProjections.size(), allProjections.size());

        Set<PlayerProjectionId> existingIds = playerProjectionRepository
                .findByYear(season)
                .stream()
                .map(PlayerProjection::getId)
                .collect(Collectors.toSet());

        List<PlayerProjection> entities = relevantProjections.stream()
                .map(p -> mapToEntity(p, season, existingIds))
                .toList();

        playerProjectionRepository.saveAll(entities);
        log.info("Saved {} projections for season {}", entities.size(), season);

        // Score the projections through all formats. computeAndSaveForSeason routes
        // to projections for this season because player_stats has no rows for it yet.
        playerScoringService.computeAndSaveForSeason(season);

        return entities.size();
    }

    private PlayerProjection mapToEntity(SleeperProjection source, int season,
                                         Set<PlayerProjectionId> existingIds) {

        SleeperProjectionData stats = source.stats();

        PlayerProjection entity = PlayerProjection.builder()
                .playerId(source.playerId())
                .year(season)
                .team(source.team())
                .source(source.company() != null ? source.company() : "unknown")
                .gamesPlayed(17)
                .passingYards(toNullableInt(stats.passingYards()))
                .passingTd(toNullableInt(stats.passingTd()))
                .interceptions(toNullableInt(stats.interceptions()))
                .rushingYards(toNullableInt(stats.rushingYards()))
                .rushingTd(toNullableInt(stats.rushingTd()))
                .receptions(toNullableInt(stats.receptions()))
                .receivingYards(toNullableInt(stats.receivingYards()))
                .receivingTd(toNullableInt(stats.receivingTd()))
                .fumblesLost(toNullableInt(stats.fumblesLost()))
                .twoPtConv(sumTwoPt(stats))
                .adpStd(toAdp(stats.adpStd()))
                .adpHalfPpr(toAdp(stats.adpHalfPpr()))
                .adpPpr(toAdp(stats.adpPpr()))
                .build();

        // Upsert: if this player+year already exists, mark existing so
        // Persistable.isNew() returns false and JPA does UPDATE not INSERT.
        if (existingIds.contains(entity.getId())) {
            entity.markExisting();
        }

        return entity;
    }

    private int toInt(Double value) {
        return value == null ? 0 : value.intValue();
    }

    private Integer toNullableInt(Double value) {
        return value == null ? null : value.intValue();
    }

    private Integer sumTwoPt(SleeperProjectionData stats) {
        int sum = toInt(stats.pass2pt()) + toInt(stats.rush2pt()) + toInt(stats.rec2pt());
        return sum == 0 ? null : sum;
    }

    // ADP: API gives a Double; column is NUMERIC. 999 is Sleeper's "unranked"
    // sentinel -> null (also keeps us inside NUMERIC(5,2)). BigDecimal.valueOf,
    // not new BigDecimal(double), to avoid binary float artifacts.
    private BigDecimal toAdp(Double value) {
        if (value == null || value >= 999.0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }
}