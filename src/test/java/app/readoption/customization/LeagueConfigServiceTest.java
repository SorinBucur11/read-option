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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("LeagueConfigService — tactics round bound and the nullable-tactics path")
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
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.HALF_PPR,
                new BigDecimal("4"), new BigDecimal("-2"), false);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
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
}
