package app.readoption.customization;

import app.readoption.customization.validation.IssueSeverity;
import app.readoption.customization.validation.ValidationIssue;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LeagueConfigController.class)
@DisplayName("LeagueConfigController — web layer: parse/refine/confirm, NEEDS_INPUT, 409")
class LeagueConfigControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private LeagueConfigService leagueConfigService;

    private static ParsedLeague parsedLeague() {
        ScoringSpec scoring = new ScoringSpec(ReceptionFormat.PPR,
                new BigDecimal("4"), new BigDecimal("-2"), false);
        RosterSpec roster = new RosterSpec(12, 1, 2, 2, 1, 1,
                Set.of(Position.RB, Position.WR, Position.TE), 0, 6);
        return new ParsedLeague(new LeagueRulesSpec(scoring, roster, null), null);
    }

    @Test
    @DisplayName("POST /parse happy path -> 200 READY with the parsed spec")
    void parseReady() throws Exception {
        when(leagueConfigService.parse("12-team PPR"))
                .thenReturn(ParseResult.of(parsedLeague(), List.of()));

        mockMvc.perform(post("/api/league/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ParseRequest("12-team PPR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.parsed.rules.scoring.basePreset").value("PPR"))
                .andExpect(jsonPath("$.issues").isEmpty());
    }

    @Test
    @DisplayName("POST /parse with a BLOCKING issue -> 200 NEEDS_INPUT carrying the issue")
    void parseNeedsInput() throws Exception {
        ValidationIssue blocking = new ValidationIssue("scoring.basePreset",
                IssueSeverity.BLOCKING, "Reception scoring was not stated and has no safe default");
        when(leagueConfigService.parse("a league"))
                .thenReturn(ParseResult.of(parsedLeague(), List.of(blocking)));

        mockMvc.perform(post("/api/league/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ParseRequest("a league"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_INPUT"))
                .andExpect(jsonPath("$.issues[0].field").value("scoring.basePreset"))
                .andExpect(jsonPath("$.issues[0].severity").value("BLOCKING"));
    }

    @Test
    @DisplayName("POST /parse with a blank description -> 400 at the HTTP boundary")
    void parseBlankDescription() throws Exception {
        mockMvc.perform(post("/api/league/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ParseRequest("  "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /refine passes current + correction + turn through")
    void refinePassesThrough() throws Exception {
        ParsedLeague current = parsedLeague();
        when(leagueConfigService.refine(eq(current), eq("make it half PPR"), eq(2)))
                .thenReturn(ParseResult.of(current, List.of()));

        mockMvc.perform(post("/api/league/refine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RefineRequest(current, "make it half PPR", 2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
    }

    @Test
    @DisplayName("POST /confirm happy path -> 200 with the generated id in the body")
    void confirmReturnsId() throws Exception {
        ParsedLeague current = parsedLeague();
        LeagueConfig saved = LeagueConfig.builder()
                .id(42L)
                .receptionFormat(ReceptionFormat.PPR)
                .passingTdPoints(new BigDecimal("4"))
                .interceptionPoints(new BigDecimal("-2"))
                .teReceptionBonus(new BigDecimal("0"))
                .teamCount(12).qbSlots(1).rbSlots(2).wrSlots(2).teSlots(1).flexSlots(1)
                .flexEligible(Set.of(Position.RB, Position.WR, Position.TE))
                .superflexSlots(0).benchSlots(6)
                .build();
        when(leagueConfigService.confirm(eq(current))).thenReturn(saved);

        mockMvc.perform(post("/api/league/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmRequest(current))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.teamCount").value(12))
                .andExpect(jsonPath("$.receptionFormat").value("PPR"));
    }

    @Test
    @DisplayName("POST /confirm with blocking issues -> 409 ProblemDetail carrying the issues")
    void confirmNotReadyIs409() throws Exception {
        ParsedLeague current = parsedLeague();
        ValidationIssue blocking = new ValidationIssue("playoff.playoffTeams",
                IssueSeverity.BLOCKING, "Playoff teams (8) cannot exceed league size (6).");
        when(leagueConfigService.confirm(eq(current)))
                .thenThrow(new LeagueConfigNotReadyException(List.of(blocking)));

        mockMvc.perform(post("/api/league/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ConfirmRequest(current))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("League Config Not Ready"))
                .andExpect(jsonPath("$.issues[0].field").value("playoff.playoffTeams"))
                .andExpect(jsonPath("$.issues[0].severity").value("BLOCKING"));
    }
}
