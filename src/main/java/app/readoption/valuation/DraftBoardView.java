package app.readoption.valuation;

import app.readoption.scoring.Position;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The VORP-ranked draft board — compact by design: counts and numbers, no
 * stat-line dumps. This DTO is the prototype for the Phase 4.2 tool result.
 */
public record DraftBoardView(
        int season,
        Map<Position, BigDecimal> replacementLevels,
        List<Row> rows
) {

    public record Row(
            String playerId,
            String name,
            Position position,
            BigDecimal projectedPoints,
            BigDecimal vorp,
            BigDecimal adp
    ) {
    }
}
