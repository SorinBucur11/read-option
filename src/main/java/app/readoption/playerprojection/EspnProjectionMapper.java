package app.readoption.playerprojection;

import app.readoption.espn.EspnPlayersResponse;
import app.readoption.espn.EspnStatId;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

@Component
public class EspnProjectionMapper {

    public static final String SOURCE = "espn";
    public static final String ADP_FORMAT = "PPR";   // leaguedefaults/3 is PPR scoring

    private final ObjectMapper objectMapper;

    public EspnProjectionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Empty if ESPN has no season-total projection for this player/season. */
    public Optional<PlayerProjectionRaw> toRaw(String playerId, int season,
                                               EspnPlayersResponse.Player espnPlayer) {
        Optional<EspnPlayersResponse.StatEntry> seasonEntry = selectSeasonProjection(espnPlayer, season);
        if (seasonEntry.isEmpty()) {
            return Optional.empty();
        }
        EspnPlayersResponse.StatEntry entry = seasonEntry.get();
        Map<String, Double> stats = entry.stats() != null ? entry.stats() : Map.of();

        PlayerProjectionRaw row = PlayerProjectionRaw.builder()
                .playerId(playerId)
                .year(season)
                .source(SOURCE)
                .passingYards(intStat(stats, EspnStatId.PASSING_YARDS))
                .passingTd(intStat(stats, EspnStatId.PASSING_TD))
                .interceptions(intStat(stats, EspnStatId.INTERCEPTIONS))
                .rushingYards(intStat(stats, EspnStatId.RUSHING_YARDS))
                .rushingTd(intStat(stats, EspnStatId.RUSHING_TD))
                .receptions(intStat(stats, EspnStatId.RECEPTIONS))
                .receivingYards(intStat(stats, EspnStatId.RECEIVING_YARDS))
                .receivingTd(intStat(stats, EspnStatId.RECEIVING_TD))
                .interceptions(intStat(stats, EspnStatId.INTERCEPTIONS))
                .fumblesLost(intStat(stats, EspnStatId.FUMBLES_LOST))
                .twoPtConv(null)   // ESPN two-pt not reliably mappable; see EspnStatId
                .adp(adp(espnPlayer))
                .adpFormat(ADP_FORMAT)
                .sourcePayload(serialize(entry))
                .build();

        return Optional.of(row);
    }

    /** The selector we proved unique per player: season + projected + season-total. */
    Optional<EspnPlayersResponse.StatEntry> selectSeasonProjection(
            EspnPlayersResponse.Player player, int season) {
        if (player.stats() == null) return Optional.empty();
        return player.stats().stream()
                .filter(s -> Integer.valueOf(season).equals(s.seasonId()))
                .filter(s -> Integer.valueOf(1).equals(s.statSourceId()))    // 1 = projected
                .filter(s -> Integer.valueOf(0).equals(s.scoringPeriodId())) // 0 = season total
                .findFirst();
    }

    private Integer intStat(Map<String, Double> stats, String id) {
        Double v = stats.get(id);
        return v == null ? null : (int) Math.round(v);   // round fractional projection to int
    }

    private BigDecimal adp(EspnPlayersResponse.Player player) {
        if (player.ownership() == null || player.ownership().averageDraftPosition() == null) {
            return null;
        }
        return BigDecimal.valueOf(player.ownership().averageDraftPosition())
                .setScale(2, RoundingMode.HALF_UP);
    }

    private String serialize(EspnPlayersResponse.StatEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            return null;   // audit payload only — never fail a row over it
        }
    }
}