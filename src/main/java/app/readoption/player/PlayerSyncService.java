package app.readoption.player;

import app.readoption.scoring.Position;
import app.readoption.sleeper.SleeperClient;
import app.readoption.sleeper.SleeperPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlayerSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlayerSyncService.class);

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

        Map<String, Player> existingById = playerRepository.findAll().stream()
                .collect(Collectors.toMap(Player::getId, Function.identity()));

        List<Player> fantasyPlayers = allPlayers.values().stream()
                .filter(sp -> Boolean.TRUE.equals(sp.active()))
                .filter(sp -> sp.position() != null)
                .filter(sp -> Position.FANTASY_POSITION_NAMES.contains(sp.position()))
                .filter(sp -> sp.playerId() != null)
                .map(this::toEntity)
                .toList();

        // Mark existing players so Hibernate does UPDATE, not SELECT+INSERT
        fantasyPlayers.forEach(p -> {
            Player existing = existingById.get(p.getId());
            if (existing != null) {
                p.markExisting();
                // Non-source-owned columns: merge copies the detached entity's FULL
                // state onto the managed row — null included — so every field the
                // blob doesn't carry must be explicitly preserved here.
                p.setEspnId(existing.getEspnId());       // writer: id-mapping enrichment stage
                p.setCreatedAt(existing.getCreatedAt()); // writer: @PrePersist, insert only
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

        return Player.builder()
                .id(sp.playerId())
                .firstName(firstName)
                .lastName(lastName)
                .fullName(fullName)
                .position(sp.position())
                .team(sp.team())   // the blob's populated field is team, NOT team_abbr (null)
                .age(sp.age())
                .yearsExp(sp.yearsExp())
                .status(sp.status())
                .active(sp.active())
                .depthChartPosition(sp.depthChartPosition())
                .depthChartOrder(sp.depthChartOrder())
                .injuryStatus(sp.injuryStatus())
                .injuryBodyPart(truncate(sp.injuryBodyPart(), 50))
                .injuryNotes(truncate(sp.injuryNotes(), 255))
                .build();
    }

    /**
     * The injury free-text fields are the only source values without a bounded
     * vocabulary; an over-length note must not fail the whole sync batch.
     */
    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
