package app.readoption.playerprojection;

import app.readoption.espn.EspnClient;
import app.readoption.espn.EspnPlayersResponse;
import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class EspnProjectionSyncService {

    private static final Logger log = LoggerFactory.getLogger(EspnProjectionSyncService.class);

    private final EspnClient espnClient;
    private final PlayerRepository playerRepository;
    private final PlayerProjectionRawRepository rawRepository;
    private final EspnProjectionMapper mapper;
    private final int currentSeason;

    public EspnProjectionSyncService(EspnClient espnClient,
                                     PlayerRepository playerRepository,
                                     PlayerProjectionRawRepository rawRepository,
                                     EspnProjectionMapper mapper,
                                     @Value("${readoption.current-season}") int currentSeason) {
        this.espnClient = espnClient;
        this.playerRepository = playerRepository;
        this.rawRepository = rawRepository;
        this.mapper = mapper;
        this.currentSeason = currentSeason;
    }

    @Transactional
    public EspnSyncResult sync() {
        int season = currentSeason;
        List<EspnPlayersResponse.Entry> entries = espnClient.fetchProjections(season);

        Set<String> existingKeys =
                rawRepository.findPlayerIdsByYearAndSource(season, EspnProjectionMapper.SOURCE);

        List<PlayerProjectionRaw> toSave = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        int noProjection = 0;

        for (EspnPlayersResponse.Entry entry : entries) {
            EspnPlayersResponse.Player ep = entry.player();
            if (ep == null) continue;

            // resolve espn id -> our canonical player id
            Optional<Player> player = playerRepository.findByEspnId(String.valueOf(ep.id()));
            if (player.isEmpty()) {
                unresolved.add(ep.id() + " (" + ep.fullName() + ")");   // the review queue
                continue;
            }

            Optional<PlayerProjectionRaw> row = mapper.toRaw(player.get().getId(), season, ep);
            if (row.isEmpty()) {
                noProjection++;       // ESPN player with no 2026 season projection (e.g. K/DST)
                continue;
            }

            PlayerProjectionRaw r = row.get();
            if (existingKeys.contains(r.getPlayerId())) {
                r.markExisting();     // upsert: UPDATE not INSERT on re-run
            }
            toSave.add(r);
        }

        rawRepository.saveAll(toSave);

        if (!unresolved.isEmpty()) {
            log.warn("ESPN sync: {} players unresolved to a player_id (see response body)", unresolved.size());
        }
        log.info("ESPN sync: fetched={}, landed={}, unresolved={}, no-projection={}",
                entries.size(), toSave.size(), unresolved.size(), noProjection);

        return new EspnSyncResult(entries.size(), toSave.size(),
                unresolved.size(), noProjection, unresolved);
    }

    public record EspnSyncResult(int fetched, int landed, int unresolved,
                                 int noProjection, List<String> unresolvedPlayers) {}
}