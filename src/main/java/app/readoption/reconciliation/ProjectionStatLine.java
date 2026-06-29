package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;
import app.readoption.scoring.StatLine;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * In-memory carrier for a staged stat line during reconciliation — a real source's
 * line or a per-stat median of source lines. Implements {@link StatLine} so the same
 * {@link app.readoption.scoring.ScoringService} can score it in the measuring-stick
 * format. Stats are {@link BigDecimal} (the values come straight off NUMERIC source
 * rows or a median of them); games_played stays Integer.
 */
@Getter
@Builder
public class ProjectionStatLine implements StatLine {

    private final BigDecimal passingYards;
    private final BigDecimal passingTd;
    private final BigDecimal interceptions;
    private final BigDecimal rushingYards;
    private final BigDecimal rushingTd;
    private final BigDecimal receptions;
    private final BigDecimal receivingYards;
    private final BigDecimal receivingTd;
    private final BigDecimal fumblesLost;
    private final BigDecimal twoPtConv;
    private final Integer gamesPlayed;

    /** Copies a landing row's stat line verbatim — the FAVOR_HIGH/LOW and single-source paths. */
    public static ProjectionStatLine from(PlayerProjectionRaw raw) {
        return ProjectionStatLine.builder()
                .passingYards(raw.getPassingYards())
                .passingTd(raw.getPassingTd())
                .interceptions(raw.getInterceptions())
                .rushingYards(raw.getRushingYards())
                .rushingTd(raw.getRushingTd())
                .receptions(raw.getReceptions())
                .receivingYards(raw.getReceivingYards())
                .receivingTd(raw.getReceivingTd())
                .fumblesLost(raw.getFumblesLost())
                .twoPtConv(raw.getTwoPtConv())
                .gamesPlayed(raw.getGamesPlayed())
                .build();
    }
}
