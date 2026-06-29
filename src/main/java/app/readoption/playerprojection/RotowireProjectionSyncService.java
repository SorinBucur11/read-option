package app.readoption.playerprojection;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lands Sleeper projections (provider = rotowire) into player_projection_raw with
 * source='rotowire'. This replaces the Phase 1 shortcut that wrote rotowire straight
 * to the mart: after Phase 2 nothing loads straight to player_projections — the only
 * mart writer is the reconciliation step. Mirrors {@link EspnProjectionSyncService}:
 * resolve to a known player, then Persistable-upsert one row per (player, season,
 * source). This is a feeder, not new logic.
 */
@Service
public class RotowireProjectionSyncService {

    private static final Logger log = LoggerFactory.getLogger(RotowireProjectionSyncService.class);

    private final SleeperClient sleeperClient;
    private final PlayerRepository playerRepository;
    private final PlayerProjectionRawRepository rawRepository;
    private final RotowireProjectionMapper mapper;

    public RotowireProjectionSyncService(SleeperClient sleeperClient,
                                         PlayerRepository playerRepository,
                                         PlayerProjectionRawRepository rawRepository,
                                         RotowireProjectionMapper mapper) {
        this.sleeperClient = sleeperClient;
        this.playerRepository = playerRepository;
        this.rawRepository = rawRepository;
        this.mapper = mapper;
    }

    @Transactional
    public int sync(int season) {
        log.info("Starting rotowire projections sync into raw for season {}", season);

        List<SleeperProjection> allProjections = sleeperClient.fetchProjections(season);

        Set<String> knownPlayerIds = playerRepository.findAll().stream()
                .map(Player::getId)
                .collect(Collectors.toSet());

        Set<String> existingKeys =
                rawRepository.findPlayerIdsByYearAndSource(season, RotowireProjectionMapper.SOURCE);

        List<PlayerProjectionRaw> toSave = new ArrayList<>();
        for (SleeperProjection projection : allProjections) {
            if (projection.playerId() == null
                    || projection.stats() == null
                    || !knownPlayerIds.contains(projection.playerId())) {
                continue;   // unknown/empty rows never reach the landing table
            }

            PlayerProjectionRaw row = mapper.toRaw(projection.playerId(), season, projection);
            if (existingKeys.contains(row.getPlayerId())) {
                row.markExisting();   // upsert: UPDATE not INSERT on re-run
            }
            toSave.add(row);
        }

        rawRepository.saveAll(toSave);
        log.info("Rotowire sync: fetched={}, landed={} into player_projection_raw for season {}",
                allProjections.size(), toSave.size(), season);
        return toSave.size();
    }
}
