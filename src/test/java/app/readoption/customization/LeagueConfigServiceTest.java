package app.readoption.customization;

import app.readoption.customization.validation.DraftTacticsValidator;
import app.readoption.customization.validation.IssueSeverity;
import app.readoption.customization.validation.LeagueRulesValidator;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeagueConfigService — validation merge, refine loop control, confirm gate")
class LeagueConfigServiceTest {

    @Mock private LeagueParsingService parsingService;
    @Mock private LeagueConfigRepository repository;

    private LeagueConfigService service;

    @BeforeEach
    void setUp() {
        // Real validators + resolver: the fixes under test live in them; only the
        // LLM seam and the repository are mocked.
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        service = new LeagueConfigService(parsingService, new LeagueRulesValidator(),
                new DraftTacticsValidator(), new LeagueRulesResolver(), new RefineDriftGuard(),
                repository, validator, new CustomizationProperties("claude-sonnet-4-6", 5));
    }

    /** A fully-stated valid league (no ASSUMPTION-triggering nulls) with the given tactics. */
    private static ParsedLeague league(DraftTactics tactics) {
        return league(tactics, 12);
    }

    private static ParsedLeague league(DraftTactics tactics, int teamCount) {
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.HALF_PPR,
                new BigDecimal("4"), new BigDecimal("-2"), false);
        RosterSpec roster = new RosterSpec(teamCount, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        return new ParsedLeague(new LeagueRulesSpec(scoring, roster, null), tactics);
    }

    @Test
    @DisplayName("a broken earliest round parses to NEEDS_INPUT")
    void badRoundBlocksParse() {
        ParsedLeague parsed = league(new DraftTactics(null, null, Map.of(Position.QB, 0), null));
        when(parsingService.parse("desc")).thenReturn(parsed);

        ParseResult result = service.parse("desc");

        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(result.issues())
                .anyMatch(issue -> issue.field().equals("tactics.earliestRoundByPosition")
                        && issue.severity() == IssueSeverity.BLOCKING);
    }

    @Test
    @DisplayName("confirm refuses a broken earliest round — nothing persists")
    void badRoundBlocksConfirm() {
        ParsedLeague parsed = league(new DraftTactics(null, null, Map.of(Position.QB, 40), null));

        assertThatThrownBy(() -> service.confirm(parsed))
                .isInstanceOf(LeagueConfigNotReadyException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("a parse failure surfaces as one BLOCKING parse issue, null parsed, NEEDS_INPUT")
    void parseFailureIsBlockingIssue() {
        when(parsingService.parse("gibberish"))
                .thenThrow(new LeagueParseException("failed to parse model output into a league spec"));

        ParseResult result = service.parse("gibberish");

        assertThat(result.parsed()).isNull();
        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).field()).isEqualTo("parse");
        assertThat(result.issues().get(0).severity()).isEqualTo(IssueSeverity.BLOCKING);
    }

    @Test
    @DisplayName("refine appends drift issues for fields the correction never addressed")
    void refineSurfacesDrift() {
        ParsedLeague current = league(null, 12);
        ParsedLeague drifted = league(null, 10);   // model 'helpfully' rewrote teamCount
        when(parsingService.refine(current, "make it half PPR")).thenReturn(drifted);

        ParseResult result = service.refine(current, "make it half PPR", 1);

        assertThat(result.parsed()).isEqualTo(drifted);
        assertThat(result.status()).isEqualTo(Status.READY);   // drift is ASSUMPTION, not a dead-lock
        assertThat(result.issues())
                .anyMatch(issue -> issue.field().equals("roster.teamCount")
                        && issue.severity() == IssueSeverity.ASSUMPTION
                        && issue.message().contains("12") && issue.message().contains("10"));
    }

    @Test
    @DisplayName("past the refine cap: no model call, BLOCKING refine issue, current object kept")
    void refinePastCapDoesNotCallModel() {
        ParsedLeague current = league(null);

        ParseResult result = service.refine(current, "one more fix", 6);   // cap is 5

        verifyNoInteractions(parsingService);
        assertThat(result.parsed()).isEqualTo(current);
        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(result.issues())
                .anyMatch(issue -> issue.field().equals("refine")
                        && issue.severity() == IssueSeverity.BLOCKING);
    }

    @Test
    @DisplayName("a failed refine turn keeps the prior object — no state loss")
    void refineFailureKeepsPriorObject() {
        ParsedLeague current = league(null);
        when(parsingService.refine(any(), anyString()))
                .thenThrow(new LeagueParseException("model call failed during refine"));

        ParseResult result = service.refine(current, "fix it", 2);

        assertThat(result.parsed()).isEqualTo(current);
        assertThat(result.issues())
                .anyMatch(issue -> issue.field().equals("parse")
                        && issue.severity() == IssueSeverity.BLOCKING);
    }

    @Test
    @DisplayName("annotation violations on rules map to BLOCKING with the rules. prefix stripped")
    void annotationViolationOnRulesIsBlocking() {
        ParsedLeague parsed = league(null, 30);   // violates @Max(20) on teamCount
        when(parsingService.parse("desc")).thenReturn(parsed);

        ParseResult result = service.parse("desc");

        assertThat(result.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(result.issues())
                .anyMatch(issue -> issue.field().equals("roster.teamCount")
                        && issue.severity() == IssueSeverity.BLOCKING
                        && issue.message().contains("30"));
    }

    @Test
    @DisplayName("where both passes flag a field, the object validator's value-bearing message wins")
    void objectValidatorMessageWinsOnOverlap() {
        // Null basePreset trips both @NotNull and the object validator.
        ScoringSpec scoring = new ScoringSpec(null, new BigDecimal("4"), new BigDecimal("-2"), false);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        ParsedLeague parsed = new ParsedLeague(new LeagueRulesSpec(scoring, roster, null), null);
        when(parsingService.parse("desc")).thenReturn(parsed);

        ParseResult result = service.parse("desc");

        assertThat(result.issues())
                .filteredOn(issue -> issue.field().equals("scoring.basePreset"))
                .hasSize(1)
                .allMatch(issue -> issue.message().contains("no safe default"));
    }

    @Test
    @DisplayName("a tactics-free league validates READY and confirms with null tactics — no NPE")
    void tacticsFreeLeagueConfirms() {
        ParsedLeague parsed = league(null);
        when(parsingService.parse("desc")).thenReturn(parsed);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.parse("desc").status()).isEqualTo(Status.READY);

        LeagueConfig saved = service.confirm(parsed);

        ArgumentCaptor<LeagueConfig> captor = ArgumentCaptor.forClass(LeagueConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getTactics()).isNull();
        assertThat(saved.getReceptionFormat()).isEqualTo(ReceptionFormat.HALF_PPR);
        assertThat(saved.getInterceptionPoints()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("confirm persists resolved scoring, not the raw spec — registry values the spec cannot carry")
    void confirmPersistsResolvedScoringNotRawSpec() {
        // tePremium is a flag with no value field, and the TD/INT points are null —
        // every number asserted below can only come from the resolver's registry.
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.PPR, null, null, true);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        ParsedLeague parsed = new ParsedLeague(new LeagueRulesSpec(scoring, roster, null), null);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.confirm(parsed);

        ArgumentCaptor<LeagueConfig> captor = ArgumentCaptor.forClass(LeagueConfig.class);
        verify(repository).save(captor.capture());
        LeagueConfig persisted = captor.getValue();
        assertThat(persisted.getTeReceptionBonus()).isEqualByComparingTo("0.5");
        assertThat(persisted.getPassingTdPoints()).isEqualByComparingTo("4");
        assertThat(persisted.getInterceptionPoints()).isEqualByComparingTo("-2");
    }
}
