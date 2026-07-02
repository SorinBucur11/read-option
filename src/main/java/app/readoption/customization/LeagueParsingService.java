package app.readoption.customization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Wraps {@link ChatClient} + {@link BeanOutputConverter} to turn a plain-English
 * league description into a {@link ParsedLeague} — the same idiom as the Phase 2
 * {@code VerdictClassifier}. The model translates intent to structure: it selects
 * presets and flags and copies stated numbers; it never originates a scoring number
 * (the spec type gives it no field to write one into).
 *
 * <p>Structured output is best-effort, not a guarantee: the model can emit malformed
 * JSON or an out-of-enum value. The {@link #parse(String)} seam is deliberately
 * separate so that failure mode is unit-testable without a live model. Any failure
 * surfaces as {@link LeagueParseException}; the orchestrator maps it to a BLOCKING
 * parse-failure issue — never a silent default.
 *
 * <p>No transaction is ever open around these calls: parsing is the REASON phase;
 * the only write happens later, in the confirm gate.
 */
@Component
public class LeagueParsingService {

    private static final Logger log = LoggerFactory.getLogger(LeagueParsingService.class);

    private final ChatClient chatClient;
    private final BeanOutputConverter<ParsedLeague> converter = new BeanOutputConverter<>(ParsedLeague.class);
    private final ObjectMapper objectMapper;
    private final String model;

    public LeagueParsingService(ChatClient.Builder chatClientBuilder,
                                CustomizationProperties properties,
                                ObjectMapper objectMapper,
                                @Value("classpath:prompts/league-parser.txt") Resource promptResource) {
        // System prompt externalized to a classpath file (a properties value spanning
        // ~18 continuation lines truncates silently on a stray trailing space and still
        // passes @NotBlank). Fail fast at startup, not on the first request; constant
        // across calls, hence defaultSystem.
        String systemPrompt = readPrompt(promptResource);
        if (systemPrompt.isBlank()) {
            throw new IllegalStateException("league-parser system prompt is empty: " + promptResource);
        }
        log.info("Loaded league-parser system prompt ({} chars) from {}",
                systemPrompt.length(), promptResource);
        this.chatClient = chatClientBuilder.defaultSystem(systemPrompt).build();
        this.objectMapper = objectMapper;
        this.model = properties.model();
    }

    private static String readPrompt(Resource promptResource) {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "could not read league-parser system prompt from " + promptResource, e);
        }
    }

    /** Free text → parsed league. Throws {@link LeagueParseException} on any failure. */
    public ParsedLeague parse(String description) {
        String userPrompt = "League description:\n" + description
                + "\n\n" + converter.getFormat();
        return convert(call(userPrompt, "parse"));
    }

    /**
     * One repair turn: the typed prior object rides along as JSON so the model repairs
     * in place — and so the caller can diff the result against {@code current} to
     * detect drift on fields the correction never addressed.
     */
    public ParsedLeague refine(ParsedLeague current, String correction) {
        String userPrompt = "Current parsed league (JSON):\n" + toJson(current)
                + "\n\nUser correction:\n" + correction
                + "\n\nApply the correction to the current parsed league. Change only what the "
                + "correction addresses; preserve every other field exactly as given."
                + "\n\n" + converter.getFormat();
        return convert(call(userPrompt, "refine"));
    }

    /**
     * Convert the model's text into a {@link ParsedLeague}. Separate from the
     * transport call so the malformed/out-of-enum failure mode can be tested directly.
     */
    public ParsedLeague convert(String modelText) {
        ParsedLeague parsed;
        try {
            parsed = converter.convert(modelText);
        } catch (RuntimeException e) {
            throw new LeagueParseException("failed to parse model output into a league spec", e);
        }
        if (parsed == null) {
            throw new LeagueParseException("model output yielded no league spec: " + modelText);
        }
        return parsed;
    }

    private String call(String userPrompt, String operation) {
        try {
            return chatClient.prompt()
                    .user(userPrompt)
                    .options(AnthropicChatOptions.builder().model(model).build())
                    .call()
                    .content();
        } catch (RuntimeException e) {
            throw new LeagueParseException("model call failed during " + operation, e);
        }
    }

    private String toJson(ParsedLeague current) {
        try {
            return objectMapper.writeValueAsString(current);
        } catch (JsonProcessingException e) {
            // Serializing our own record should never fail; if it does, that's a bug
            // in this codebase, not a model failure — don't dress it as a parse issue.
            throw new IllegalStateException("could not serialize current ParsedLeague", e);
        }
    }
}
