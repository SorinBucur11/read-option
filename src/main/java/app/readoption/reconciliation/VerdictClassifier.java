package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjectionRaw;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Wraps {@link ChatClient} + {@link BeanOutputConverter} to turn a contested player's
 * per-stat breakdown into a {@link Verdict}. The model classifies the <i>shape</i> of
 * the disagreement and returns an enum verdict + enum confidence + short rationale; it
 * never produces a stat or a point.
 *
 * <p>Structured output is best-effort, not a guarantee: the model can return an
 * out-of-enum value or extra prose and the parse can throw. The {@link #parse} seam is
 * deliberately separate so the failure mode is unit-testable without a live model. Any
 * failure surfaces as {@link VerdictClassificationException}; the caller falls back.
 */
@Component
public class VerdictClassifier {

    private static final Logger log = LoggerFactory.getLogger(VerdictClassifier.class);

    private final ChatClient chatClient;
    private final BeanOutputConverter<Verdict> converter = new BeanOutputConverter<>(Verdict.class);
    private final String model;

    public VerdictClassifier(ChatClient.Builder chatClientBuilder, ReconcileProperties properties) {
        // System prompt is externalized to ReconcileProperties so it can be tuned without
        // recompiling during prompt iteration; it is constant across calls, hence defaultSystem.
        this.chatClient = chatClientBuilder.defaultSystem(properties.systemPrompt()).build();
        this.model = properties.model();
    }

    public String model() {
        return model;
    }

    /**
     * Classify one contested player. Throws {@link VerdictClassificationException} on
     * transport or parse failure — the caller maps that to the LLM_FALLBACK route.
     */
    public Verdict classify(ContestedPlayer player) {
        String userPrompt = buildUserPrompt(player);
        String response;
        try {
            response = chatClient.prompt()
                    .user(userPrompt)
                    .options(AnthropicChatOptions.builder().model(model).build())
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw new VerdictClassificationException(
                    "model call failed for " + player.name(), e);
        }
        return parse(response);
    }

    /**
     * Parse the model's text into a {@link Verdict}. Separate from the transport call so
     * the malformed/out-of-enum failure mode can be tested directly. Throws on a null
     * result, a null verdict/confidence, or any converter error.
     */
    public Verdict parse(String modelText) {
        Verdict verdict;
        try {
            verdict = converter.convert(modelText);
        } catch (RuntimeException e) {
            throw new VerdictClassificationException("failed to parse model output", e);
        }
        if (verdict == null || verdict.verdict() == null || verdict.confidence() == null) {
            throw new VerdictClassificationException(
                    "model output missing verdict or confidence: " + modelText);
        }
        return verdict;
    }

    private String buildUserPrompt(ContestedPlayer player) {
        StringBuilder sb = new StringBuilder();
        sb.append("Player: ").append(player.name())
                .append(", ").append(player.position())
                .append(", ").append(player.team()).append('\n');
        sb.append("Sources disagree. Per-stat breakdown (").append(player.measuringStick())
                .append(" points):\n");
        for (ContestedPlayer.Source source : player.sources()) {
            sb.append("- ").append(source.name())
                    .append(" (").append(source.points()).append(" pts): ")
                    .append(statBreakdown(source.line())).append('\n');
        }
        sb.append("Highest-points source: ").append(player.highSource())
                .append(" (").append(player.highPoints()).append("). ");
        sb.append("Lowest: ").append(player.lowSource())
                .append(" (").append(player.lowPoints()).append(").\n");
        sb.append("Where does the gap live (volume / efficiency / touchdowns)? Classify: ")
                .append("TRUST_CONSENSUS, FAVOR_HIGH_SOURCE, FAVOR_LOW_SOURCE, or FLAG_UNCERTAIN.\n\n");
        sb.append(converter.getFormat());
        return sb.toString();
    }

    /** Compact non-null stat listing so the model sees where the gap actually lives. */
    private String statBreakdown(PlayerProjectionRaw r) {
        StringBuilder sb = new StringBuilder();
        appendStat(sb, "passYd", r.getPassingYards());
        appendStat(sb, "passTd", r.getPassingTd());
        appendStat(sb, "int", r.getInterceptions());
        appendStat(sb, "rushYd", r.getRushingYards());
        appendStat(sb, "rushTd", r.getRushingTd());
        appendStat(sb, "rec", r.getReceptions());
        appendStat(sb, "recYd", r.getReceivingYards());
        appendStat(sb, "recTd", r.getReceivingTd());
        appendStat(sb, "fumLost", r.getFumblesLost());
        appendStat(sb, "2pt", r.getTwoPtConv());
        return sb.length() == 0 ? "(no stats)" : sb.toString().strip();
    }

    private void appendStat(StringBuilder sb, String label, BigDecimal value) {
        if (value != null && value.signum() != 0) {
            sb.append(label).append('=').append(value.stripTrailingZeros().toPlainString()).append("  ");
        }
    }
}
