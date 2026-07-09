package app.readoption.playerprojection;

import app.readoption.sleeper.SleeperProjection;
import app.readoption.sleeper.SleeperProjectionData;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Maps a Sleeper projection (provider = rotowire) into a {@link PlayerProjectionRaw}
 * landing row. Mirrors {@link EspnProjectionMapper}: one row per (player, season,
 * source) at the same staging grain as ESPN, so reconciliation sees ≥2 sources per
 * player. Stat values are kept as NUMERIC (no integer rounding) since V7.
 */
@Component
public class RotowireProjectionMapper {

    public static final String SOURCE = "rotowire";

    /** gamesPlayed is a season-total assumption, same as the prior direct-to-mart path. */
    private static final int SEASON_GAMES = 17;

    private final ObjectMapper objectMapper;

    public RotowireProjectionMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public PlayerProjectionRaw toRaw(String playerId, int season, SleeperProjection projection) {
        SleeperProjectionData stats = projection.stats();

        return PlayerProjectionRaw.builder()
                .playerId(playerId)
                .year(season)
                .source(SOURCE)
                .team(projection.team())
                .gamesPlayed(SEASON_GAMES)
                .passingYards(decimal(stats.passingYards()))
                .passingTd(decimal(stats.passingTd()))
                .interceptions(decimal(stats.interceptions()))
                .rushingYards(decimal(stats.rushingYards()))
                .rushingTd(decimal(stats.rushingTd()))
                .receptions(decimal(stats.receptions()))
                .receivingYards(decimal(stats.receivingYards()))
                .receivingTd(decimal(stats.receivingTd()))
                .fumblesLost(decimal(stats.fumblesLost()))
                .twoPtConv(sumTwoPt(stats))
                .adpStd(toAdp(stats.adpStd()))
                .adpHalfPpr(toAdp(stats.adpHalfPpr()))
                .adpPpr(toAdp(stats.adpPpr()))
                .sourcePayload(serialize(projection))
                .build();
    }

    // Mirror EspnProjectionMapper: store the typed object we mapped from, null-safe.
    private String serialize(SleeperProjection projection) {
        try {
            return objectMapper.writeValueAsString(projection);
        } catch (JacksonException e) {
            return null;   // audit payload only — never fail a row over it
        }
    }

    // BigDecimal.valueOf, not new BigDecimal(double), to avoid binary-float artifacts.
    private BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumTwoPt(SleeperProjectionData stats) {
        BigDecimal sum = BigDecimal.ZERO
                .add(orZero(stats.pass2pt()))
                .add(orZero(stats.rush2pt()))
                .add(orZero(stats.rec2pt()));
        return sum.signum() == 0 ? null : sum.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal orZero(Double value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    // 999 is Sleeper's "unranked" sentinel -> null (also keeps us inside NUMERIC(6,2)).
    private BigDecimal toAdp(Double value) {
        if (value == null || value >= 999.0) {
            return null;
        }
        return BigDecimal.valueOf(value);
    }
}
