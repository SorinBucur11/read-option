package app.readoption.agent;

import app.readoption.player.PlayerNotFoundException;
import app.readoption.player.PlayerRepository;
import app.readoption.player.SeasonScore;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerstats.PlayerStats;
import app.readoption.playerstats.PlayerStatsRepository;
import app.readoption.player.Player;
import app.readoption.scoring.ScoringRules;
import app.readoption.scoring.ScoringService;
import app.readoption.team.NflTeam;
import app.readoption.team.NflTeamRepository;
import app.readoption.team.TeamContextService;
import app.readoption.team.TeamSchedule;
import app.readoption.team.TeamScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Real {@link ScoringService} (never mocked math), fixture repositories. The Barkley
 * fixture is his real 2026 Rotowire source line — the line the 208.50/226.00/243.50
 * regression anchors trace to. All BigDecimal assertions by compareTo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileScoringService — history + projection under resolved rules, no preset default")
class ProfileScoringServiceTest {

    private static final int CURRENT_SEASON = 2026;

    private static final ScoringRules STANDARD_RULES =
            ScoringRules.of(new BigDecimal("0"), new BigDecimal("4"),
                    ScoringRules.DEFAULT_INTERCEPTION_POINTS, ScoringRules.NO_TE_BONUS);
    private static final ScoringRules HALF_PPR_RULES =
            ScoringRules.of(new BigDecimal("0.5"), new BigDecimal("4"),
                    ScoringRules.DEFAULT_INTERCEPTION_POINTS, ScoringRules.NO_TE_BONUS);
    private static final ScoringRules PPR_RULES =
            ScoringRules.of(new BigDecimal("1.0"), new BigDecimal("4"),
                    ScoringRules.DEFAULT_INTERCEPTION_POINTS, ScoringRules.NO_TE_BONUS);
    private static final ScoringRules TE_PREMIUM_RULES =
            ScoringRules.of(new BigDecimal("0.5"), new BigDecimal("4"),
                    ScoringRules.DEFAULT_INTERCEPTION_POINTS, new BigDecimal("0.5"));

    @Mock private PlayerRepository playerRepository;
    @Mock private PlayerStatsRepository statsRepository;
    @Mock private PlayerProjectionRepository projectionRepository;
    @Mock private NflTeamRepository nflTeamRepository;
    @Mock private TeamScheduleRepository teamScheduleRepository;

    /**
     * Real TeamContextService over mocked repositories: unstubbed lookups return
     * empty, so the fixture default team ("XX") degrades loudly without per-test
     * stubbing — the LEFT-JOIN posture is always on.
     */
    private ProfileScoringService service() {
        return new ProfileScoringService(playerRepository, statsRepository,
                projectionRepository, new ScoringService(),
                new TeamContextService(nflTeamRepository, teamScheduleRepository),
                CURRENT_SEASON);
    }

    /** Barkley's 2026 Rotowire line — the source of the regression anchors. */
    private static PlayerProjection barkleyProjection() {
        return PlayerProjection.builder()
                .playerId("4866").year(CURRENT_SEASON).gamesPlayed(17)
                .rushingYards(new BigDecimal("1220")).rushingTd(new BigDecimal("8"))
                .receptions(new BigDecimal("35")).receivingYards(new BigDecimal("265"))
                .receivingTd(new BigDecimal("2")).fumblesLost(new BigDecimal("1"))
                .twoPtConv(new BigDecimal("1"))
                .adpStd(new BigDecimal("13.40")).adpHalfPpr(new BigDecimal("14.20"))
                .adpPpr(new BigDecimal("19.50"))
                .build();
    }

    private void stubBarkley() {
        when(playerRepository.findById("4866"))
                .thenReturn(Optional.of(player("4866", "Saquon Barkley", "RB")));
        when(statsRepository.findByPlayerId("4866")).thenReturn(List.of());
        when(projectionRepository.findByPlayerIdAndYear("4866", CURRENT_SEASON))
                .thenReturn(Optional.of(barkleyProjection()));
    }

    @Test
    @DisplayName("Barkley anchors hold on the uniform path: 208.50 / 226.00 / 243.50")
    void barkleyAnchors() {
        stubBarkley();
        when(projectionRepository.countBetterAdpAtPosition(
                eq(CURRENT_SEASON), eq("RB"), anyString(), any(BigDecimal.class)))
                .thenReturn(2L);

        assertThat(service().profile("4866", STANDARD_RULES).projection().totalPoints())
                .isEqualByComparingTo("208.50");
        assertThat(service().profile("4866", HALF_PPR_RULES).projection().totalPoints())
                .isEqualByComparingTo("226.00");
        assertThat(service().profile("4866", PPR_RULES).projection().totalPoints())
                .isEqualByComparingTo("243.50");
    }

    @Test
    @DisplayName("ADP reads the format-matched column; positional rank = better ADPs + 1")
    void adpFollowsResolvedRules() {
        stubBarkley();
        when(projectionRepository.countBetterAdpAtPosition(
                CURRENT_SEASON, "RB", "HALF_PPR", new BigDecimal("14.20"))).thenReturn(2L);

        PlayerProfileView profile = service().profile("4866", HALF_PPR_RULES);

        assertThat(profile.projection().adp()).isEqualByComparingTo("14.20");
        assertThat(profile.projection().positionalRank()).isEqualTo(3);
    }

    @Test
    @DisplayName("Mahomes 2025 history line scores INTs at the league's -2, not the provider's -1")
    void mahomesInterceptionRule() {
        when(playerRepository.findById("4046"))
                .thenReturn(Optional.of(player("4046", "Patrick Mahomes", "QB")));
        // Real 2025 actuals: 3587 pass yds, 22 pass TD, 11 INT, 422 rush yds,
        // 5 rush TD, 1 rec, -10 rec yds, 2 two-pt conversions.
        PlayerStats line = PlayerStats.builder()
                .playerId("4046").year(2025).games(17).gamesPlayed(14)
                .passingYards(3587).passingTd(22).interceptions(11)
                .rushingYards(422).rushingTd(5)
                .receptions(1).receivingYards(-10)
                .twoPtConv(2)
                .build();
        when(statsRepository.findByPlayerId("4046")).thenReturn(List.of(line));
        when(projectionRepository.findByPlayerIdAndYear("4046", CURRENT_SEASON))
                .thenReturn(Optional.empty());

        ScoringRules sixPtTd = ScoringRules.of(new BigDecimal("0"), new BigDecimal("6"),
                ScoringRules.DEFAULT_INTERCEPTION_POINTS, ScoringRules.NO_TE_BONUS);
        PlayerProfileView profile = service().profile("4046", sixPtTd);

        // 143.48 + 132 - 22 + 42.2 + 30 - 1 + 4 = 328.68 (11 INTs at -2 = -22, not -11)
        assertThat(profile.history()).hasSize(1);
        assertThat(profile.history().get(0).totalPoints()).isEqualByComparingTo("328.68");
        assertThat(profile.projection()).isNull();
    }

    @Test
    @DisplayName("TE premium scores a TE's history line higher than standard rules do")
    void tePremiumOnHistory() {
        when(playerRepository.findById("T1"))
                .thenReturn(Optional.of(player("T1", "TE One", "TE")));
        PlayerStats line = PlayerStats.builder()
                .playerId("T1").year(2025).games(17).gamesPlayed(17)
                .receptions(80).receivingYards(800).receivingTd(6)
                .build();
        when(statsRepository.findByPlayerId("T1")).thenReturn(List.of(line));
        when(projectionRepository.findByPlayerIdAndYear("T1", CURRENT_SEASON))
                .thenReturn(Optional.empty());

        BigDecimal premium = service().profile("T1", TE_PREMIUM_RULES)
                .history().get(0).totalPoints();
        BigDecimal halfPpr = service().profile("T1", HALF_PPR_RULES)
                .history().get(0).totalPoints();

        // 80 receptions x 0.5 bonus = +40.00 on top of the half-PPR line
        assertThat(premium.subtract(halfPpr)).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("history is capped at the last five completed seasons, ascending")
    void historyWindow() {
        when(playerRepository.findById("4866"))
                .thenReturn(Optional.of(player("4866", "Saquon Barkley", "RB")));
        List<PlayerStats> seasons = List.of(
                statSeason(2020), statSeason(2023), statSeason(2021), statSeason(2026),
                statSeason(2025), statSeason(2022), statSeason(2024));
        when(statsRepository.findByPlayerId("4866")).thenReturn(seasons);
        when(projectionRepository.findByPlayerIdAndYear("4866", CURRENT_SEASON))
                .thenReturn(Optional.empty());

        PlayerProfileView profile = service().profile("4866", STANDARD_RULES);

        // 2026 (current) excluded, 2020 dropped by the 5-season cap, ascending order.
        assertThat(profile.history()).extracting(SeasonScore::year)
                .containsExactly(2021, 2022, 2023, 2024, 2025);
    }

    @Test
    @DisplayName("unknown player -> PlayerNotFoundException (404)")
    void unknownPlayer() {
        when(playerRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatExceptionOfType(PlayerNotFoundException.class)
                .isThrownBy(() -> service().profile("nope", STANDARD_RULES));
    }

    // ----- Phase 4.3 role block -----

    /** Bare profile stubs so the role-block tests don't drag scoring fixtures along. */
    private void stubProfileScaffolding(Player fixture) {
        when(playerRepository.findById(fixture.getId())).thenReturn(Optional.of(fixture));
        when(statsRepository.findByPlayerId(fixture.getId())).thenReturn(List.of());
        when(projectionRepository.findByPlayerIdAndYear(fixture.getId(), CURRENT_SEASON))
                .thenReturn(Optional.empty());
    }

    private static Player contextPlayer(String id, String team, String depthPosition,
                                        Integer depthOrder) {
        Player fixture = player(id, "Context Player", "WR", team, true);
        fixture.setDepthChartPosition(depthPosition);
        fixture.setDepthChartOrder(depthOrder);
        return fixture;
    }

    @Test
    @DisplayName("starter on a known team: role block, empty depthChartAhead, bye + early opponents")
    void starterEnrichment() {
        Player starter = contextPlayer("S1", "PHI", "RB", 1);
        stubProfileScaffolding(starter);
        when(playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "PHI", "RB", 1))
                .thenReturn(List.of());
        when(nflTeamRepository.findById("PHI")).thenReturn(Optional.of(
                NflTeam.builder().abbrev("PHI").espnAbbrev("PHI")
                        .name("Philadelphia Eagles").byeWeek(9).build()));
        when(teamScheduleRepository
                .findByTeamAndSeasonAndWeekLessThanEqualOrderByWeekAsc("PHI", CURRENT_SEASON, 3))
                .thenReturn(List.of(
                        TeamSchedule.builder().team("PHI").season(CURRENT_SEASON).week(1)
                                .opponent("DAL").isHome(true).build(),
                        TeamSchedule.builder().team("PHI").season(CURRENT_SEASON).week(2)
                                .opponent("KC").isHome(false).build()));

        PlayerProfileView profile = service().profile("S1", STANDARD_RULES);

        assertThat(profile.team()).isEqualTo("PHI");
        assertThat(profile.depthChartPosition()).isEqualTo("RB");
        assertThat(profile.depthChartOrder()).isEqualTo(1);
        assertThat(profile.depthChartAhead()).isEmpty();   // starter: empty, not omitted
        assertThat(profile.injuryStatus()).isEqualTo("no injury reported");
        assertThat(profile.byeWeek()).isEqualTo("9");
        assertThat(profile.earlyOpponents())
                .containsExactly("W1 vs DAL (home)", "W2 at KC (away)");
    }

    @Test
    @DisplayName("backup: depthChartAhead carries the ladder names in order")
    void backupDepthChartAhead() {
        Player backup = contextPlayer("B1", "CIN", "SWR", 3);
        stubProfileScaffolding(backup);
        when(playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "CIN", "SWR", 3))
                .thenReturn(List.of(
                        player("A1", "Slot One", "WR", "CIN", true),
                        player("A2", "Slot Two", "WR", "CIN", true)));

        PlayerProfileView profile = service().profile("B1", STANDARD_RULES);

        assertThat(profile.depthChartPosition()).isEqualTo("SWR");   // raw, never normalized
        assertThat(profile.depthChartAhead()).containsExactly("Slot One", "Slot Two");
    }

    @Test
    @DisplayName("injured: the raw injury trio passes through untranslated")
    void injuredPassthrough() {
        Player injured = contextPlayer("I1", "KC", "QB", 1);
        injured.setInjuryStatus("Questionable");
        injured.setInjuryBodyPart("Ankle");
        injured.setInjuryNotes("Surgery");
        stubProfileScaffolding(injured);
        when(playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "KC", "QB", 1))
                .thenReturn(List.of());

        PlayerProfileView profile = service().profile("I1", STANDARD_RULES);

        assertThat(profile.injuryStatus()).isEqualTo("Questionable");
        assertThat(profile.injuryBodyPart()).isEqualTo("Ankle");
        assertThat(profile.injuryNotes()).isEqualTo("Surgery");
    }

    @Test
    @DisplayName("partial trio (status null, lingering notes): whole block reads NO_INJURY, notes suppressed")
    void partialInjuryTrioReadsNoInjury() {
        // Counterfactual fixture: today's Sleeper blob never carries notes without a
        // status (verified against the full payload, 2026-07-07). That invariant is
        // the vendor's to break, silently, on any future sync — this pins OUR read
        // boundary for the day it does: status is the authoritative flag, and a
        // floating "Surgery" must not serialize as an active concern.
        Player lingering = contextPlayer("L1", "KC", "QB", 1);
        lingering.setInjuryBodyPart("Ankle");
        lingering.setInjuryNotes("Surgery");
        stubProfileScaffolding(lingering);
        when(playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "KC", "QB", 1))
                .thenReturn(List.of());

        PlayerProfileView profile = service().profile("L1", STANDARD_RULES);

        assertThat(profile.injuryStatus()).isEqualTo("no injury reported");
        assertThat(profile.injuryBodyPart()).isNull();   // omitted by NON_NULL
        assertThat(profile.injuryNotes()).isNull();      // omitted by NON_NULL
    }

    @Test
    @DisplayName("free agent (null team): every context field degrades loudly, row NOT dropped")
    void freeAgentDegradation() {
        stubProfileScaffolding(contextPlayer("F1", null, null, null));

        PlayerProfileView profile = service().profile("F1", STANDARD_RULES);

        assertThat(profile.team()).isEqualTo(TeamContextService.NO_TEAM);
        assertThat(profile.depthChartPosition()).isEqualTo("role unconfirmed");
        assertThat(profile.depthChartOrder()).isNull();
        assertThat(profile.depthChartAhead()).isNull();   // omitted when role unconfirmed
        assertThat(profile.byeWeek()).isEqualTo(TeamContextService.BYE_UNKNOWN_NO_TEAM);
        assertThat(profile.earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_UNAVAILABLE);
    }

    @Test
    @DisplayName("stale OAK team: degradation, never a silent remap to LV")
    void unknownTeamDegradation() {
        Player stale = contextPlayer("O1", "OAK", "TE", 1);
        stubProfileScaffolding(stale);
        when(playerRepository
                .findByTeamAndDepthChartPositionAndDepthChartOrderLessThanOrderByDepthChartOrderAsc(
                        "OAK", "TE", 1))
                .thenReturn(List.of());
        when(nflTeamRepository.findById("OAK")).thenReturn(Optional.empty());

        PlayerProfileView profile = service().profile("O1", STANDARD_RULES);

        assertThat(profile.team()).isEqualTo("OAK");   // the raw fact, not a guess
        assertThat(profile.byeWeek()).isEqualTo(TeamContextService.BYE_UNKNOWN_NO_TEAM);
        assertThat(profile.earlyOpponents())
                .containsExactly(TeamContextService.OPPONENTS_UNAVAILABLE);
    }

    private static PlayerStats statSeason(int year) {
        return PlayerStats.builder()
                .playerId("4866").year(year).games(17).gamesPlayed(16)
                .rushingYards(1000)
                .build();
    }
}
