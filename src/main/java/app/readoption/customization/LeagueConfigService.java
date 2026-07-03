package app.readoption.customization;

import app.readoption.customization.validation.DraftTacticsValidator;
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
 * client we can 400) and the object-level validators ({@link LeagueRulesValidator}
 * for the engine-bound half, {@link DraftTacticsValidator} for the strategy-bound
 * half). Where both hit the same field, the object validator's value-bearing message
 * wins. Annotation violations under {@code tactics} surface as ASSUMPTION, not
 * BLOCKING — tactics are soft-validated (their consumer is an LLM), rules are
 * hard-validated (their consumer is the engine). The one exception is
 * {@code earliestRoundByPosition}, whose mechanical Phase 4 consumer earns it a
 * BLOCKING bound in the tactics validator.
 */
@Service
public class LeagueConfigService {

    private static final Logger log = LoggerFactory.getLogger(LeagueConfigService.class);

    private final LeagueParsingService parsingService;
    private final LeagueRulesValidator rulesValidator;
    private final DraftTacticsValidator tacticsValidator;
    private final LeagueRulesResolver resolver;
    private final RefineDriftGuard driftGuard;
    private final LeagueConfigRepository repository;
    private final Validator validator;
    private final CustomizationProperties properties;

    public LeagueConfigService(LeagueParsingService parsingService,
                               LeagueRulesValidator rulesValidator,
                               DraftTacticsValidator tacticsValidator,
                               LeagueRulesResolver resolver,
                               RefineDriftGuard driftGuard,
                               LeagueConfigRepository repository,
                               Validator validator,
                               CustomizationProperties properties) {
        this.parsingService = parsingService;
        this.rulesValidator = rulesValidator;
        this.tacticsValidator = tacticsValidator;
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
        ParseResult result = ParseResult.of(parsed, validate(parsed));
        // Outcome only — the description is free text up to 5000 chars; log its
        // length, never its content.
        log.info("League parse ({} chars) -> {} ({} blocking, {} assumption)",
                description.length(), result.status(),
                count(result.issues(), IssueSeverity.BLOCKING),
                count(result.issues(), IssueSeverity.ASSUMPTION));
        return result;
    }

    public ParseResult refine(ParsedLeague current, String correction, int turn) {
        int cap = properties.maxRefineTurns();
        if (turn > cap) {
            // The repair loop must terminate: past the cap, return the partial object
            // plus its unresolved issues — no model call.
            log.info("League refine turn {} past cap {} — refused without a model call", turn, cap);
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
        List<ValidationIssue> drift = driftGuard.diff(current, refined);
        issues.addAll(drift);
        ParseResult result = ParseResult.of(refined, issues);
        log.info("League refine turn {} -> {} ({} blocking, {} assumption, {} changed field{})",
                turn, result.status(),
                count(result.issues(), IssueSeverity.BLOCKING),
                count(result.issues(), IssueSeverity.ASSUMPTION),
                drift.size(), drift.size() == 1 ? "" : "s");
        return result;
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
            log.info("League confirm refused — {} blocking issue{} ({}), nothing persisted",
                    count(issues, IssueSeverity.BLOCKING),
                    count(issues, IssueSeverity.BLOCKING) == 1 ? "" : "s",
                    issues.stream()
                            .filter(issue -> issue.severity() == IssueSeverity.BLOCKING)
                            .map(ValidationIssue::field)
                            .collect(Collectors.joining(", ")));
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
        List<ValidationIssue> objectIssues = new ArrayList<>(parsed.rules() == null
                ? List.of()
                : rulesValidator.validate(parsed.rules()));
        // Tactics validator is null-safe on null tactics and null map.
        objectIssues.addAll(tacticsValidator.validate(parsed.tactics()));

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

    private long count(List<ValidationIssue> issues, IssueSeverity severity) {
        return issues.stream().filter(issue -> issue.severity() == severity).count();
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
