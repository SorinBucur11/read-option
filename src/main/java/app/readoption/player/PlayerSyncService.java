package app.readoption.player;

import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PlayerSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSyncService.class);
    private static final Set<String> FANTASY_POSITIONS = Set.of("QB", "RB", "WR", "TE", "K", "DEF");

    private final SleeperClient sleeperClient;
    private final PlayerRepository playerRepository;

    public PlayerSyncService(SleeperClient sleeperClient, PlayerRepository playerRepository) {
        this.sleeperClient = sleeperClient;
        this.playerRepository = playerRepository;
    }

    @Transactional
    public int syncPlayers() {
        log.info("Starting player sync...");

        Map<String, SleeperPlayer> allPlayers = sleeperClient.fetchAllPlayers();

        // Get existing player IDs for comparison
        Set<String> existingIds = playerRepository.findAll().stream()
                .map(Player::getId)
                .collect(Collectors.toSet());

        List<Player> fantasyPlayers = allPlayers.values().stream()
                .filter(sp -> Boolean.TRUE.equals(sp.active()))
                .filter(sp -> sp.position() != null)
                .filter(sp -> FANTASY_POSITIONS.contains(sp.position()))
                .filter(sp -> sp.playerId() != null)
                .map(this::toEntity)
                .toList();

        // Mark existing players so Hibernate does UPDATE, not SELECT+INSERT
        fantasyPlayers.forEach(p -> {
            if (existingIds.contains(p.getId())) {
                p.markExisting();
            }
        });

        playerRepository.saveAll(fantasyPlayers);

        log.info("Synced {} fantasy-relevant players", fantasyPlayers.size());
        return fantasyPlayers.size();
    }

    private Player toEntity(SleeperPlayer sp) {
        String firstName = sp.firstName() != null ? sp.firstName() : "";
        String lastName = sp.lastName() != null ? sp.lastName() : "";
        String fullName = sp.fullName();

        if (fullName == null || fullName.isBlank()) {
            fullName = (firstName + " " + lastName).trim();
            if (fullName.isEmpty()) {
                fullName = "Unknown";
            }
        }

        Player player = new Player(sp.playerId(), firstName, lastName, fullName);
        player.setPosition(sp.position());
        player.setTeam(sp.team());
        player.setAge(sp.age());
        player.setYearsExp(sp.yearsExp());
        player.setStatus(sp.status());
        player.setActive(sp.active());
        player.setUpdatedAt(LocalDateTime.now());
        return player;
    }
}
