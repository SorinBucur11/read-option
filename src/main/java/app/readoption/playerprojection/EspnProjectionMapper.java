package app.readoption.playerprojection;

import app.readoption.espn.EspnPlayersResponse;
import app.readoption.espn.EspnStatId;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

@Component
public class EspnProjectionMapper {

    public static final String SOURCE = "espn";

    private static final int SEASON_GAMES = 17;   // mirror rotowire; a season projection means a full season

    private final ObjectMapper objectMapper;

    public EspnProjectionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Empty if ESPN has no season-total projection for this player/season. */
    public Optional<PlayerProjectionRaw> toRaw(String playerId, int season, String team,
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
                .team(team)
                .gamesPlayed(SEASON_GAMES)
                .passingYards(decimalStat(stats, EspnStatId.PASSING_YARDS))
                .passingTd(decimalStat(stats, EspnStatId.PASSING_TD))
                .interceptions(decimalStat(stats, EspnStatId.INTERCEPTIONS))
                .rushingYards(decimalStat(stats, EspnStatId.RUSHING_YARDS))
                .rushingTd(decimalStat(stats, EspnStatId.RUSHING_TD))
                .receptions(decimalStat(stats, EspnStatId.RECEPTIONS))
                .receivingYards(decimalStat(stats, EspnStatId.RECEIVING_YARDS))
                .receivingTd(decimalStat(stats, EspnStatId.RECEIVING_TD))
                .fumblesLost(decimalStat(stats, EspnStatId.FUMBLES_LOST))
                .twoPtConv(null)   // ESPN two-pt not reliably mappable; see EspnStatId
                // Per-format ADP (V11) stays null: ESPN has no adp_std/half_ppr/ppr split.
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

    private BigDecimal decimalStat(Map<String, Double> stats, String id) {
        Double v = stats.get(id);
        // Preserve the fractional projection (NUMERIC(7,2) since V7): rounding here
        // would inject noise into the cross-source dispersion signal. BigDecimal.valueOf,
        // not new BigDecimal(double), to avoid binary-float artifacts.
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private String serialize(EspnPlayersResponse.StatEntry entry) {
        try {
            return objectMapper.writeValueAsString(entry);
        } catch (JacksonException e) {
            return null;   // audit payload only — never fail a row over it
        }
    }
}