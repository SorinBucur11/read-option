package app.readoption.valuation;

import app.readoption.scoring.Position;

import java.math.BigDecimal;

/**
 * One already-scored player entering the valuation engine: identity, position, and
 * projected points under the league's rules. The engine computed {@code points};
 * this record just carries it.
 */
public record PlayerValue(
        String playerId,
        Position position,
        BigDecimal points
) {
}
