package app.readoption.reconciliation;

import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRaw;
import app.readoption.playerprojection.PlayerProjectionRawRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.scoring.ScoringResult;
import app.readoption.scoring.ScoringService;
import app.readoption.scoring.StatLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReconciliationService — routing, verdict application, phasing")
class ReconciliationServiceTest {

    private static final int SEASON = 2026;

    @Mock private PlayerProjectionRawRepository rawRepository;
    @Mock private PlayerRepository playerRepository;
    @Mock private ScoringService scoringService;
    @Mock private VerdictClassifier classifier;
    @Mock private ReconciliationWriter writer;
    @Mock private PlayerScoringService playerScoringService;
    @Mock private PriorSeasonContextRetriever priorSeasonContextRetriever;

    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        ReconcileProperties props = new ReconcileProperties(
                new BigDecimal("0.12"), new BigDecimal("20.0"), MeasuringStick.PPR, "claude-sonnet-4-6",
                "test system prompt");
        service = new ReconciliationService(rawRepository, playerRepository, scoringService,
                classifier, writer, playerScoringService, priorSeasonContextRetriever, props);

        // Measuring-stick points are driven off receivingYards so each test controls the spread.
        when(scoringService.calculate(any(), any(), any())).thenAnswer(inv -> {
            StatLine s = inv.getArgument(0);
            Number ry = s.getReceivingYards();
            BigDecimal pts = ry == null ? BigDecimal.ZERO : new BigDecimal(ry.toString());
            return new ScoringResult(pts, BigDecimal.ZERO);
        });
        when(classifier.model()).thenReturn("claude-sonnet-4-6");
        when(writer.write(anyInt(), any())).thenReturn(Set.of("P1"));
        when(playerScoringService.computeAndSaveForPlayers(anyInt(), any())).thenReturn(6);
        when(playerRepository.findById(any())).thenReturn(Optional.empty());
        // Default: no prior history. Tests that assert on the retrieved actuals override this.
        when(priorSeasonContextRetriever.retrieve(anySet(), anyInt())).thenReturn(Map.of());
    }

    /** A source row whose measuring-stick points equal {@code points}. */
    private static PlayerProjectionRaw raw(String playerId, String source, double points) {
        return PlayerProjectionRaw.builder()
                .playerId(playerId).year(SEASON).source(source).team("KC").gamesPlayed(17)
                .receivingYards(BigDecimal.valueOf(points))
                .build();
    }

    private List<PlayerReconciliation> captureWrite() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PlayerReconciliation>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).write(anyInt(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("dry-run records CV distribution and writes nothing, calls no model")
    void dryRunWritesNothing() {
        // espn 100 vs rotowire 200 → cv = 100/300 ≈ 0.333 > 0.12 (contested at threshold)
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(
                raw("P1", "espn", 100), raw("P1", "rotowire", 200)));

        ReconciliationReport report = service.reconcile(SEASON, true);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.cvDistribution()).isNotNull();
        assertThat(report.cvDistribution().twoPlusSourcePlayers()).isEqualTo(1);
        assertThat(report.cvDistribution().contestedAtThreshold()).isEqualTo(1);
        verify(writer, never()).write(anyInt(), any());
        verify(classifier, never()).classify(any());
        verify(playerScoringService, never()).computeAndSaveForPlayers(anyInt(), any());
    }

    @Test
    @DisplayName("points floor skips a non-draftable player before any staging")
    void floorSkipsNonDraftable() {
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(
                raw("P1", "espn", 10), raw("P1", "rotowire", 15)));   // max 15 < 20 floor

        ReconciliationReport report = service.reconcile(SEASON, false);

        assertThat(report.skipped()).isEqualTo(1);
        assertThat(captureWrite()).isEmpty();
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("single source stages that line with SINGLE_SOURCE route, no model")
    void singleSourceNoModel() {
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(raw("P1", "espn", 100)));

        ReconciliationReport report = service.reconcile(SEASON, false);

        assertThat(report.reconciledSingleSource()).isEqualTo(1);
        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("espn");
        assertThat(r.audit().getRoute()).isEqualTo(Route.SINGLE_SOURCE.name());
        assertThat(r.audit().getCv()).isNull();
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("agreement (CV ≤ threshold) writes the median with no model call")
    void agreementUsesConsensus() {
        // 100 vs 105 → cv = 5/205 ≈ 0.024 ≤ 0.12
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(
                raw("P1", "espn", 100), raw("P1", "rotowire", 105)));

        ReconciliationReport report = service.reconcile(SEASON, false);

        assertThat(report.reconciledConsensus()).isEqualTo(1);
        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("consensus");
        assertThat(r.audit().getRoute()).isEqualTo(Route.CONSENSUS.name());
        verify(classifier, never()).classify(any());
    }

    @Test
    @DisplayName("FAVOR_HIGH_SOURCE stages the highest-points source line")
    void favorHighSource() {
        contested();
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.FAVOR_HIGH_SOURCE, Confidence.HIGH, "volume"));

        ReconciliationReport report = service.reconcile(SEASON, false);

        assertThat(report.reconciledLlm()).isEqualTo(1);
        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("rotowire");   // the 200-pt source
        assertThat(r.audit().getRoute()).isEqualTo(Route.LLM.name());
        assertThat(r.audit().getLlmVerdict()).isEqualTo("FAVOR_HIGH_SOURCE");
        assertThat(r.audit().getConfidence()).isEqualTo("HIGH");
        assertThat(r.audit().getModel()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("FAVOR_LOW_SOURCE stages the lowest-points source line")
    void favorLowSource() {
        contested();
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.FAVOR_LOW_SOURCE, Confidence.MEDIUM, "regression"));

        service.reconcile(SEASON, false);

        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("espn");   // the 100-pt source
        assertThat(r.audit().getLlmVerdict()).isEqualTo("FAVOR_LOW_SOURCE");
    }

    @Test
    @DisplayName("TRUST_CONSENSUS writes the median, provenance = consensus")
    void trustConsensus() {
        contested();
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.TRUST_CONSENSUS, Confidence.HIGH, "agree"));

        service.reconcile(SEASON, false);

        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("consensus");
        assertThat(r.audit().getRoute()).isEqualTo(Route.LLM.name());
        assertThat(r.audit().getLlmVerdict()).isEqualTo("TRUST_CONSENSUS");
    }

    @Test
    @DisplayName("FLAG_UNCERTAIN writes the median; review state lives on the audit row")
    void flagUncertain() {
        contested();
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.FLAG_UNCERTAIN, Confidence.LOW, "unclear"));

        service.reconcile(SEASON, false);

        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("consensus");
        assertThat(r.audit().getRoute()).isEqualTo(Route.LLM.name());
        assertThat(r.audit().getLlmVerdict()).isEqualTo("FLAG_UNCERTAIN");
    }

    @Test
    @DisplayName("a thrown classifier falls back to the median (LLM_FALLBACK), batch survives")
    void modelFailureFallsBack() {
        contested();
        when(classifier.classify(any())).thenThrow(
                new VerdictClassificationException("boom"));

        ReconciliationReport report = service.reconcile(SEASON, false);

        assertThat(report.fellBack()).isEqualTo(1);
        PlayerReconciliation r = captureWrite().get(0);
        assertThat(r.line().chosenSource()).isEqualTo("consensus");
        assertThat(r.audit().getRoute()).isEqualTo(Route.LLM_FALLBACK.name());
        assertThat(r.audit().getLlmVerdict()).isNull();
    }

    @Test
    @DisplayName("real run re-scores exactly the players the writer touched")
    void reScoresTouchedPlayers() {
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(raw("P1", "espn", 100)));

        service.reconcile(SEASON, false);

        verify(playerScoringService).computeAndSaveForPlayers(SEASON, Set.of("P1"));
    }

    @Test
    @DisplayName("prior actuals are retrieved once, for the ≥2-source id set only, not per player")
    void retrievesPriorActualsOnceForTwoSourceIds() {
        // P1 contested (2 sources), P2 single-source (never contested → never needs actuals).
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(
                raw("P1", "espn", 100), raw("P1", "rotowire", 200),
                raw("P2", "espn", 100)));
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.TRUST_CONSENSUS, Confidence.HIGH, "ok"));

        service.reconcile(SEASON, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> idCaptor = ArgumentCaptor.forClass(Set.class);
        verify(priorSeasonContextRetriever, times(1)).retrieve(idCaptor.capture(), eq(SEASON));
        assertThat(idCaptor.getValue()).containsExactly("P1");
    }

    @Test
    @DisplayName("retrieved actuals are threaded onto the ContestedPlayer handed to the classifier")
    void contestedPlayerCarriesPriorActuals() {
        contested();
        List<SeasonActuals> actuals = List.of(
                new SeasonActuals(2025, 16, null, null, 280, 2, 18, 140, 1));
        when(priorSeasonContextRetriever.retrieve(anySet(), eq(SEASON)))
                .thenReturn(Map.of("P1", actuals));
        when(classifier.classify(any())).thenReturn(
                new Verdict(ReconciliationVerdict.FAVOR_LOW_SOURCE, Confidence.HIGH, "role"));

        service.reconcile(SEASON, false);

        ArgumentCaptor<ContestedPlayer> captor = ArgumentCaptor.forClass(ContestedPlayer.class);
        verify(classifier).classify(captor.capture());
        assertThat(captor.getValue().priorActuals()).isEqualTo(actuals);
    }

    @Test
    @DisplayName("dry run never retrieves prior actuals (no model call to feed)")
    void dryRunSkipsRetrieval() {
        contested();

        service.reconcile(SEASON, true);

        verify(priorSeasonContextRetriever, never()).retrieve(anySet(), anyInt());
    }

    /** espn 100 vs rotowire 200 → cv ≈ 0.333 > 0.12, so the player is contested. */
    private void contested() {
        when(rawRepository.findByYear(SEASON)).thenReturn(List.of(
                raw("P1", "espn", 100), raw("P1", "rotowire", 200)));
    }
}
