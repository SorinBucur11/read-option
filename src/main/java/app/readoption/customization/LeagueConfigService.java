package app.readoption.customization;

import app.readoption.customization.validation.IssueSeverity;
import app.readoption.customization.validation.LeagueRulesValidator;
import app.readoption.customization.validation.ValidationIssue;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates parse → refine → confirm. Stateless: the partial object travels in
 * the payloads, so there is no {@code ChatMemory} and no session store — the state
 * of the conversation is a typed object.
 *
 * <p>Phasing: parse/refine are the REASON phase (LLM calls, <b>no transaction
 * open</b>); {@link #confirm} is the WRITE phase — the only writer, transactional,
 * and containing no LLM call. {@code READY} status is not a commit; nothing persists
 * before confirm.
 *
 * <p>Validation runs in two passes and both come back as one issue list: the Jakarta
 * annotation pass (run programmatically — the object came from the model, not from a
 * client we can 400) and the object-level {@link LeagueRulesValidator}. Where both
 * hit the same field, the object validator's value-bearing message wins. Violations
 * under {@code tactics} surface as ASSUMPTION, not BLOCKING — tactics are
 * soft-validated (their consumer is an LLM), rules are hard-validated (their
 * consumer is the engine).
 */
@Service
public class LeagueConfigService {

    private static final Logger log = LoggerFactory.getLogger(LeagueConfigService.class);

    private final LeagueParsingService parsingService;
    private final LeagueRulesValidator rulesValidator;
    private final LeagueRulesResolver resolver;
    private final RefineDriftGuard driftGuard;
    private final LeagueConfigRepository repository;
    private final Validator validator;
    private final CustomizationProperties properties;

    public LeagueConfigService(LeagueParsingService parsingService,
                               LeagueRulesValidator rulesValidator,
                               LeagueRulesResolver resolver,
                               RefineDriftGuard driftGuard,
                               LeagueConfigRepository repository,
                               Validator validator,
                               CustomizationProperties properties) {
        this.parsingService = parsingService;
        this.rulesValidator = rulesValidator;
        this.resolver = resolver;
        this.driftGuard = driftGuard;
        this.repository = repository;
        this.validator = validator;
        this.properties = properties;
    }

    public ParseResult parse(String description) {
        ParsedLeague parsed;
        try {
            parsed = parsingService.parse(description);
        } catch (LeagueParseException e) {
            log.warn("League parse failed", e);
            return ParseResult.of(null, List.of(parseFailureIssue()));
        }
        return ParseResult.of(parsed, validate(parsed));
    }

    public ParseResult refine(ParsedLeague current, String correction, int turn) {
        int cap = properties.maxRefineTurns();
        if (turn > cap) {
            // The repair loop must terminate: past the cap, return the partial object
            // plus its unresolved issues — no model call.
            List<ValidationIssue> issues = new ArrayList<>(validate(current));
            issues.add(new ValidationIssue("refine", IssueSeverity.BLOCKING,
                    "Refine turn cap (" + cap + ") reached — state the remaining details "
                            + "explicitly in a fresh parse instead of another correction."));
            return ParseResult.of(current, issues);
        }

        ParsedLeague refined;
        try {
            refined = parsingService.refine(current, correction);
        } catch (LeagueParseException e) {
            // Keep the prior object — a failed repair turn must not lose state.
            log.warn("League refine failed", e);
            List<ValidationIssue> issues = new ArrayList<>(validate(current));
            issues.add(parseFailureIssue());
            return ParseResult.of(current, issues);
        }

        List<ValidationIssue> issues = new ArrayList<>(validate(refined));
        issues.addAll(driftGuard.diff(current, refined));
        return ParseResult.of(refined, issues);
    }

    /**
     * The commit gate and the flow's only writer. Re-validates from scratch (a READY
     * status in an earlier response proves nothing about the payload we were just
     * handed), resolves the spec deterministically, persists. No LLM call in here —
     * the transaction never spans one.
     */
    @Transactional
    public LeagueConfig confirm(ParsedLeague current) {
        List<ValidationIssue> issues = validate(current);
        boolean blocked = issues.stream()
                .anyMatch(issue -> issue.severity() == IssueSeverity.BLOCKING);
        if (blocked) {
            throw new LeagueConfigNotReadyException(issues);
        }

        LeagueRules resolved = resolver.resolve(current.rules());
        LeagueConfig saved = repository.save(toEntity(current, resolved));
        log.info("Confirmed league config id={} ({} teams, {} base preset)",
                saved.getId(), saved.getTeamCount(), saved.getReceptionFormat());
        return saved;
    }

    private List<ValidationIssue> validate(ParsedLeague parsed) {
        List<ValidationIssue> annotationIssues = validator.validate(parsed).stream()
                .map(this::toIssue)
                .toList();
        List<ValidationIssue> objectIssues = parsed.rules() == null
                ? List.of()
                : rulesValidator.validate(parsed.rules());

        // Merge, preferring the object validator's value-bearing message on a field
        // both passes flagged (e.g. a null basePreset).
        Set<String> covered = objectIssues.stream()
                .map(ValidationIssue::field)
                .collect(Collectors.toSet());
        List<ValidationIssue> merged = new ArrayList<>(objectIssues);
        annotationIssues.stream()
                .filter(issue -> !covered.contains(issue.field()))
                .forEach(merged::add);
        return merged;
    }

    private ValidationIssue toIssue(ConstraintViolation<ParsedLeague> violation) {
        String path = violation.getPropertyPath().toString();
        // Issue fields are relative to the spec ("scoring.basePreset"), matching the
        // object validator's naming; tactics paths keep their prefix.
        String field = path.startsWith("rules.") ? path.substring("rules.".length()) : path;
        IssueSeverity severity = path.startsWith("tactics")
                ? IssueSeverity.ASSUMPTION
                : IssueSeverity.BLOCKING;
        return new ValidationIssue(field, severity,
                field + " " + violation.getMessage() + " (was: " + violation.getInvalidValue() + ")");
    }

    private ValidationIssue parseFailureIssue() {
        return new ValidationIssue("parse", IssueSeverity.BLOCKING,
                "The description could not be parsed into a league spec — there is no safe "
                        + "default league config. Please restate your league (scoring, roster, playoffs).");
    }

    private LeagueConfig toEntity(ParsedLeague parsed, LeagueRules resolved) {
        var scoring = resolved.scoring();
        var roster = resolved.roster();
        PlayoffFormat playoff = resolved.playoff();
        return LeagueConfig.builder()
                .receptionFormat(parsed.rules().scoring().basePreset())
                .passingTdPoints(scoring.passingTdPoints())
                .interceptionPoints(scoring.interceptionPoints())
                .teReceptionBonus(scoring.teReceptionBonus())
                .teamCount(roster.teams())
                .qbSlots(roster.qbSlots())
                .rbSlots(roster.rbSlots())
                .wrSlots(roster.wrSlots())
                .teSlots(roster.teSlots())
                .flexSlots(roster.flexSlots())
                .flexEligible(roster.flexEligible())
                .superflexSlots(roster.superflexSlots())
                .benchSlots(roster.benchSlots())
                .playoffTeams(playoff == null ? null : playoff.playoffTeams())
                .playoffStartWeek(playoff == null ? null : playoff.playoffStartWeek())
                .playoffEndWeek(playoff == null ? null : playoff.playoffEndWeek())
                .tactics(parsed.tactics())
                .build();
    }
}
