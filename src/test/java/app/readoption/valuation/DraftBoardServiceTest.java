package app.readoption.valuation;

import app.readoption.customization.LeagueConfig;
import app.readoption.customization.LeagueConfigRepository;
import app.readoption.draft.DraftPick;
import app.readoption.draft.DraftPickRepository;
import app.readoption.draft.DraftSession;
import app.readoption.draft.DraftSessionNotFoundException;
import app.readoption.draft.DraftSessionRepository;
import app.readoption.draft.DraftStatus;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import app.readoption.scoring.ScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Fixture mart rows + a known rules object; the real {@link ScoringService} does the
 * in-memory scoring (never mocked math). All BigDecimal assertions by compareTo.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DraftBoardService — in-memory scoring, static baseline, VORP ranking, ADP bucket")
class DraftBoardServiceTest {

    private static final long SESSION_ID = 1L;
    private static final long CONFIG_ID = 42L;
    private static final int SEASON = 2026;

    @Mock private DraftSessionRepository sessionRepository;
    @Mock private DraftPickRepository pickRepository;
    @Mock private LeagueConfigRepository leagueConfigRepository;
    @Mock private PlayerProjectionRepository projectionRepository;
    @Mock private PlayerRepository playerRepository;

    private DraftBoardService service() {
        return new DraftBoardService(sessionRepository, pickRepository, leagueConfigRepository,
                projectionRepository, playerRepository, new ScoringService());
    }

    private static DraftSession session() {
        return DraftSession.builder()
                .id(SESSION_ID).leagueConfigId(CONFIG_ID).season(SEASON)
                .teamCount(2).userSlot(1).totalRounds(4).status(DraftStatus.ACTIVE)
                .build();
    }

    /** Half-PPR-ish custom config with a TE premium — NOT one of the six presets. */
    private static LeagueConfig halfPprTePremiumConfig() {
        return LeagueConfig.builder()
                .id(CONFIG_ID)
                .receptionFormat(ReceptionFormat.HALF_PPR)
                .passingTdPoints(new BigDecimal("4"))
                .interceptionPoints(new BigDecimal("-2"))
                .teReceptionBonus(new BigDecimal("0.5"))
                .teamCount(2)
                .qbSlots(0).rbSlots(1).wrSlots(0).teSlots(0).flexSlots(0)
                .flexEligible(Set.of(Position.RB, Position.WR))
                .superflexSlots(0).benchSlots(3)
                .build();
    }

    private static PlayerProjection rbProjection(String id, String rushingYards) {
        return PlayerProjection.builder()
                .playerId(id).year(SEASON).gamesPlayed(17)
                .rushingYards(new BigDecimal(rushingYards))
                .adpStd(new BigDecimal(id.substring(1) + ".10"))
                .adpHalfPpr(new BigDecimal(id.substring(1) + ".20"))
                .adpPpr(new BigDecimal(id.substring(1) + ".30"))
                .build();
    }

    /** RBs at 100/80/50/30 rushing yards -> 10.00/8.00/5.00/3.00 points. */
    private void stubRbBoard() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(halfPprTePremiumConfig()));
        when(projectionRepository.findByYear(SEASON)).thenReturn(List.of(
                rbProjection("R1", "100"), rbProjection("R2", "80"),
                rbProjection("R3", "50"), rbProjection("R4", "30")));
        when(playerRepository.findAllById(any())).thenReturn(List.of(
                player("R1", "RB One", "RB"), player("R2", "RB Two", "RB"),
                player("R3", "RB Three", "RB"), player("R4", "RB Four", "RB")));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("VORP = points minus the position's replacement level, ranked descending")
    void vorpRankingAndValues() {
        stubRbBoard();

        DraftBoardView board = service().getBoard(SESSION_ID, null, 20);

        // 2 teams x 1 RB slot reserved -> replacement = 3rd RB = 5.00
        assertThat(board.replacementLevels().get(Position.RB)).isEqualByComparingTo("5.00");
        assertThat(board.rows()).extracting(DraftBoardView.Row::playerId)
                .containsExactly("R1", "R2", "R3", "R4");
        assertThat(board.rows().get(0).projectedPoints()).isEqualByComparingTo("10.00");
        assertThat(board.rows().get(0).vorp()).isEqualByComparingTo("5.00");
        assertThat(board.rows().get(1).vorp()).isEqualByComparingTo("3.00");
        assertThat(board.rows().get(2).vorp()).isEqualByComparingTo("0.00");
        assertThat(board.rows().get(3).vorp()).isEqualByComparingTo("-2.00");
        assertThat(board.season()).isEqualTo(SEASON);
    }

    @Test
    @DisplayName("a drafted player is absent, but the replacement baseline does not move")
    void draftedPlayerExcludedBaselineStatic() {
        stubRbBoard();
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of(
                DraftPick.builder().sessionId(SESSION_ID).overallPickNo(1).playerId("R1").build()));

        DraftBoardView board = service().getBoard(SESSION_ID, null, 20);

        assertThat(board.rows()).extracting(DraftBoardView.Row::playerId)
                .containsExactly("R2", "R3", "R4");
        // static pre-draft baseline: still computed over the full pool incl. R1
        assertThat(board.replacementLevels().get(Position.RB)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("custom half-PPR-ish rules (0.5/reception) select the half-PPR ADP column")
    void halfPprBucketSelectsHalfPprAdp() {
        stubRbBoard();

        DraftBoardView board = service().getBoard(SESSION_ID, null, 20);

        // rbProjection R1 carries adp_std=1.10, adp_half_ppr=1.20, adp_ppr=1.30
        assertThat(board.rows().get(0).adp()).isEqualByComparingTo("1.20");
    }

    @Test
    @DisplayName("a standard preset config selects the standard ADP column")
    void standardConfigSelectsStandardAdp() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        LeagueConfig standard = LeagueConfig.builder()
                .id(CONFIG_ID)
                .receptionFormat(ReceptionFormat.STANDARD)
                .passingTdPoints(new BigDecimal("4"))
                .interceptionPoints(new BigDecimal("-2"))
                .teReceptionBonus(new BigDecimal("0"))
                .teamCount(2)
                .qbSlots(0).rbSlots(1).wrSlots(0).teSlots(0).flexSlots(0)
                .flexEligible(Set.of(Position.RB, Position.WR))
                .superflexSlots(0).benchSlots(3)
                .build();
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(standard));
        when(projectionRepository.findByYear(SEASON)).thenReturn(List.of(rbProjection("R1", "100")));
        when(playerRepository.findAllById(any())).thenReturn(List.of(player("R1", "RB One", "RB")));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());

        DraftBoardView board = service().getBoard(SESSION_ID, null, 20);

        assertThat(board.rows().get(0).adp()).isEqualByComparingTo("1.10");
    }

    @Test
    @DisplayName("the TE reception premium applies on the uniform scoring path")
    void tePremiumApplies() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session()));
        when(leagueConfigRepository.findById(CONFIG_ID)).thenReturn(Optional.of(halfPprTePremiumConfig()));
        PlayerProjection te = PlayerProjection.builder()
                .playerId("T1").year(SEASON).gamesPlayed(17)
                .receptions(new BigDecimal("10")).build();
        PlayerProjection wr = PlayerProjection.builder()
                .playerId("W1").year(SEASON).gamesPlayed(17)
                .receptions(new BigDecimal("10")).build();
        when(projectionRepository.findByYear(SEASON)).thenReturn(List.of(te, wr));
        when(playerRepository.findAllById(any())).thenReturn(List.of(
                player("T1", "TE One", "TE"), player("W1", "WR One", "WR")));
        when(pickRepository.findBySessionIdOrderByOverallPickNo(SESSION_ID)).thenReturn(List.of());

        DraftBoardView board = service().getBoard(SESSION_ID, null, 20);

        DraftBoardView.Row teRow = board.rows().stream()
                .filter(r -> r.playerId().equals("T1")).findFirst().orElseThrow();
        DraftBoardView.Row wrRow = board.rows().stream()
                .filter(r -> r.playerId().equals("W1")).findFirst().orElseThrow();
        assertThat(teRow.projectedPoints()).isEqualByComparingTo("10.00");   // 10 x (0.5 + 0.5)
        assertThat(wrRow.projectedPoints()).isEqualByComparingTo("5.00");    // 10 x 0.5
    }

    @Test
    @DisplayName("position filter and limit narrow the rows without moving the baselines")
    void positionFilterAndLimit() {
        stubRbBoard();

        DraftBoardView board = service().getBoard(SESSION_ID, Position.RB, 2);

        assertThat(board.rows()).hasSize(2);
        assertThat(board.rows()).extracting(DraftBoardView.Row::playerId)
                .containsExactly("R1", "R2");
        assertThat(board.replacementLevels().get(Position.RB)).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("unknown session -> DraftSessionNotFoundException (404)")
    void unknownSession() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatExceptionOfType(DraftSessionNotFoundException.class)
                .isThrownBy(() -> service().getBoard(SESSION_ID, null, 20));
    }
}
