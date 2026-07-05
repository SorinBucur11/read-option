package app.readoption.agent;

import app.readoption.customization.DraftTactics;
import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import app.readoption.scoring.ScoringRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Assembles the agent's pre-injected system prompt: the externalized role/behavior
 * template (loaded once, fail-fast — same idiom as the league-parser prompt) plus
 * the per-request snapshot facts: the league's resolved scoring summary and the
 * user's {@link DraftTactics}. These are snapshots of a confirmed config — never
 * tools. The roster and unfilled slots are dynamic and deliberately absent here;
 * they come from the {@code get_draft_state} tool.
 */
@Component
public class AgentPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptBuilder.class);

    private final String template;

    public AgentPromptBuilder(
            @Value("classpath:prompts/draft-agent-system.txt") Resource promptResource) {
        this.template = readPrompt(promptResource);
        if (template.isBlank()) {
            throw new IllegalStateException(
                    "draft-agent system prompt is empty: " + promptResource);
        }
        log.info("Loaded draft-agent system prompt ({} chars) from {}",
                template.length(), promptResource);
    }

    /**
     * {@code teamCount} and {@code totalRounds} are the <b>session's</b> frozen
     * snapshots, deliberately not re-derived from the config here — snapshot facts
     * are never re-derived mid-session, so correctness never rests on the config
     * happening to still agree (the 4.1 teamCount principle).
     */
    public String build(ScoringRules rules, LeagueSettings settings, DraftTactics tactics,
                        int teamCount, int totalRounds) {
        return template
                + "\n\nThis league (resolved, authoritative):\n"
                + leagueSummary(rules, settings, teamCount, totalRounds)
                + "\n\nUser draft tactics (standing preferences to weigh):\n"
                + tacticsSummary(tactics);
    }

    /** Short human-readable line, e.g. "Half-PPR (0.5/reception), TE premium +0.5, ...". */
    private String leagueSummary(ScoringRules rules, LeagueSettings settings,
                                 int teamCount, int totalRounds) {
        StringJoiner scoring = new StringJoiner(", ");
        scoring.add(receptionLabel(rules.pointsPerReception()));
        if (rules.teReceptionBonus().signum() > 0) {
            scoring.add("TE premium +" + plain(rules.teReceptionBonus()) + "/reception");
        }
        scoring.add(plain(rules.passingTdPoints()) + " points per passing TD");
        scoring.add(plain(rules.interceptionPoints()) + " per interception");

        StringJoiner roster = new StringJoiner(", ");
        addSlot(roster, settings.qbSlots(), Position.QB.name());
        addSlot(roster, settings.rbSlots(), Position.RB.name());
        addSlot(roster, settings.wrSlots(), Position.WR.name());
        addSlot(roster, settings.teSlots(), Position.TE.name());
        addSlot(roster, settings.flexSlots(), "FLEX");
        addSlot(roster, settings.superflexSlots(), "SUPERFLEX");
        addSlot(roster, settings.benchSlots(), "bench");

        return "Scoring: " + scoring + ".\n"
                + "Roster: " + teamCount + " teams; per team " + roster
                + " (" + totalRounds + " rounds).";
    }

    private String tacticsSummary(DraftTactics tactics) {
        if (tactics == null) {
            return "No draft tactics stated.";
        }
        List<String> lines = new ArrayList<>();
        if (tactics.positionalStrategy() != null) {
            lines.add("Positional strategy: " + tactics.positionalStrategy());
        }
        if (tactics.riskPosture() != null) {
            lines.add("Risk posture: " + tactics.riskPosture());
        }
        if (tactics.earliestRoundByPosition() != null
                && !tactics.earliestRoundByPosition().isEmpty()) {
            StringJoiner gates = new StringJoiner("; ");
            for (Map.Entry<Position, Integer> gate : tactics.earliestRoundByPosition().entrySet()) {
                gates.add("no " + gate.getKey() + " before round " + gate.getValue());
            }
            lines.add("Positional round gates: " + gates + ".");
        }
        if (tactics.freeformNotes() != null && !tactics.freeformNotes().isEmpty()) {
            lines.add("Notes (verbatim from the user):");
            tactics.freeformNotes().forEach(note -> lines.add("- " + note));
        }
        return lines.isEmpty() ? "No draft tactics stated." : String.join("\n", lines);
    }

    private String receptionLabel(BigDecimal pointsPerReception) {
        if (pointsPerReception.compareTo(BigDecimal.ZERO) == 0) {
            return "Standard scoring (0 per reception)";
        }
        if (pointsPerReception.compareTo(new BigDecimal("0.5")) == 0) {
            return "Half-PPR (0.5/reception)";
        }
        if (pointsPerReception.compareTo(BigDecimal.ONE) == 0) {
            return "Full PPR (1/reception)";
        }
        return "Custom receptions (" + plain(pointsPerReception) + "/reception)";
    }

    private void addSlot(StringJoiner roster, int slots, String label) {
        if (slots > 0) {
            roster.add(slots + " " + label);
        }
    }

    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String readPrompt(Resource promptResource) {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not read draft-agent system prompt from " + promptResource, e);
        }
    }
}
