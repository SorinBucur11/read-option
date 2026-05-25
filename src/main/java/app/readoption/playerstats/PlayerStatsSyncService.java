package app.readoption.playerstats;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperPlayerStats;
import app.readoption.sleeper.SleeperStatsData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlayerStatsSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerStatsSyncService.class);

    private final SleeperClient sleeperClient;
    private final PlayerStatsRepository playerStatsRepository;
    private final PlayerRepository playerRepository;

    public PlayerStatsSyncService(SleeperClient sleeperClient,
                                  PlayerStatsRepository playerStatsRepository,
                                  PlayerRepository playerRepository) {
        this.sleeperClient = sleeperClient;
        this.playerStatsRepository = playerStatsRepository;
        this.playerRepository = playerRepository;
    }

    @Transactional
    public int syncStats(int season) {
        log.info("Starting stats sync for seaseon {}", season);

        List<SleeperPlayerStats> allStats = sleeperClient.fetchStats(season);

        Set<String> knownPlayerIds = playerRepository.findAll().stream()
                .map(Player::getId)
                .collect(Collectors.toSet());

        List<SleeperPlayerStats> relevantStats = allStats.stream()
                .filter(s -> s.playerId() != null)
                .filter(s -> s.stats() != null)
                .filter(s -> knownPlayerIds.contains(s.playerId()))
                .toList();

        log.info("Filtered to {} relevant stat lines (from {} total)",
                relevantStats.size(), allStats.size());

        Set<PlayerStatsId> existingIds = playerStatsRepository
                .findByYear(season)
                .stream()
                .map(PlayerStats::getId)
                .collect(Collectors.toSet());

        int totalGamesInSeason = season >= 2021 ? 17 : 16;

        List<PlayerStats> entities = relevantStats.stream()
                .map(s -> mapToEntity(s, season, totalGamesInSeason, existingIds))
                .toList();

        playerStatsRepository.saveAll(entities);
        log.info("Saved {} stat lines for season {}", entities.size(), season);

        return entities.size();
    }

    private PlayerStats mapToEntity(SleeperPlayerStats source, int season,
                                    int totalGames, Set<PlayerStatsId> existingIds) {
        PlayerStats entity = new PlayerStats(source.playerId(), season);
        SleeperStatsData stats = source.stats();

        entity.setTeam(source.team());
        entity.setGames(totalGames);
        entity.setGamesPlayed(toInt(stats.gamesPlayed()));
        entity.setPassAttempts(toNullableInt(stats.passAttempts()));
        entity.setPassesCompleted(toNullableInt(stats.passesCompleted()));
        entity.setPassingYards(toNullableInt(stats.passingYards()));
        entity.setPassingTd(toNullableInt(stats.passingTd()));
        entity.setInterceptions(toNullableInt(stats.interceptions()));
        entity.setRushingAttempts(toNullableInt(stats.rushingAttempts()));
        entity.setRushingYards(toNullableInt(stats.rushingYards()));
        entity.setRushingTd(toNullableInt(stats.rushingTd()));
        entity.setTargets(toNullableInt(stats.targets()));
        entity.setReceptions(toNullableInt(stats.receptions()));
        entity.setReceivingYards(toNullableInt(stats.receivingYards()));
        entity.setReceivingTd(toNullableInt(stats.receivingTd()));
        entity.setTwoPtConv(sumTwoPt(stats));

        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        // Upsert logic: if this player+year already exists, mark as existing
        // so Persistable.isNew() returns false and JPA does UPDATE not INSERT
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

    private Integer sumTwoPt(SleeperStatsData stats) {
        int sum = toInt(stats.pass2pt()) + toInt(stats.rush2pt()) + toInt(stats.rec2pt());
        return sum == 0 ? null : sum;
    }

}
