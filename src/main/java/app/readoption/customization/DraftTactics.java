package app.readoption.customization;

import app.readoption.scoring.Position;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * The strategy-bound half of {@link ParsedLeague} — soft-validated, because its
 * consumer is the Phase 4 draft agent (an LLM), which can reason over prose. That is
 * why this record has a free-text tail and {@link LeagueRulesSpec} does not: a
 * deterministic engine consumes the rules, so prose is useless there.
 *
 * <p>Closed-enum leans + one parameterized constraint + an open tail:
 * <ul>
 *   <li>{@code positionalStrategy} / {@code riskPosture} — {@code null} means the user
 *       stated no lean; the parser must not invent one.</li>
 *   <li>{@code earliestRoundByPosition} — e.g. {@code {QB: 10}} = "no QB before round 10".</li>
 *   <li>{@code freeformNotes} — open-set tactics (stacking, handcuffing, personal
 *       quirks) land here verbatim. They graduate to typed fields only when a consumer
 *       that can act on them exists — do not add a typed {@code stackQbWithReceiver}
 *       field; its consumer (teams/schedule data + correlation logic) is not built yet.</li>
 * </ul>
 */
public record DraftTactics(
        PositionalStrategy positionalStrategy,
        RiskPosture riskPosture,
        Map<Position, Integer> earliestRoundByPosition,
        @Size(max = 20) List<@Size(max = 500) String> freeformNotes) {
}
