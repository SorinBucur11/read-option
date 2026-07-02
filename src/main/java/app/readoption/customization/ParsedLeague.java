package app.readoption.customization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * The LLM parser's output type — the root of what one
 * {@code BeanOutputConverter<ParsedLeague>} call yields.
 *
 * <p><b>Critical boundary — do not collapse.</b> This is the model's output type, not
 * the engine's input type. It is a narrow <i>spec</i>: a preset, flags, and a few
 * extracted numbers. A deterministic {@link LeagueRulesResolver} maps it onto the
 * engine's {@link LeagueRules}; the flag→number registry lives in the resolver, so the
 * model can say "TE premium" but has no field to write {@code 1.5} into. The LLM never
 * emits {@code ScoringRules} directly.
 *
 * <p>One call yields both partitions: {@code rules} is engine-bound and hard-validated;
 * {@code tactics} is strategy-bound (consumed by the Phase 4 agent) and soft-validated.
 * {@code @Valid} cascades the nested Jakarta constraints when validated programmatically.
 */
public record ParsedLeague(
        @NotNull @Valid LeagueRulesSpec rules,
        @Valid DraftTactics tactics) {
}
