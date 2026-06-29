package app.readoption.reconciliation;

import app.readoption.player.Player;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjectionRaw;
import app.readoption.playerprojection.PlayerProjectionRawRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.scoring.ScoringFormat;
import app.readoption.scoring.ScoringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciliation orchestrator (Option A). The deterministic engine produces every
 * number; the model only classifies a disagreement into an enum, applied as a
 * selection rule over stat lines already in hand.
 *
 * <p>Phased deliberately so no DB transaction is ever held across a model call:
 * <b>READ</b> (load all source rows) → <b>REASON</b> (score in memory, route, classify
 * — no transaction) → <b>WRITE</b> (bounded txn on {@link ReconciliationWriter}) →
 * <b>RE-SCORE</b> (touched players only, via {@link PlayerScoringService}). This bean
 * is intentionally not {@code @Transactional}: it spans the model calls.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String CONSENSUS = "consensus";

    private final PlayerProjectionRawRepository rawRepository;
    private final PlayerRepository playerRepository;
    private final ScoringService scoringService;
    private final VerdictClassifier classifier;
    private final ReconciliationWriter writer;
    private final PlayerScoringService playerScoringService;
    private final ReconcileProperties properties;

    public ReconciliationService(PlayerProjectionRawRepository rawRepository,
                                 PlayerRepository playerRepository,
                                 ScoringService scoringService,
                                 VerdictClassifier classifier,
                                 ReconciliationWriter writer,
                                 PlayerScoringService playerScoringService,
                                 ReconcileProperties properties) {
        this.rawRepository = rawRepository;
        this.playerRepository = playerRepository;
        this.scoringService = scoringService;
        this.classifier = classifier;
        this.writer = writer;
        this.playerScoringService = playerScoringService;
        this.properties = properties;
    }

    public ReconciliationReport reconcile(int season, boolean dryRun) {
        ScoringFormat stick = properties.measuringStick().scoringFormat();
        BigDecimal pointsFloor = properties.pointsFloor();
        double threshold = properties.cvThreshold().doubleValue();

        // READ — group every source row for the season by player.
        Map<String, List<PlayerProjectionRaw>> bySource = groupByPlayer(rawRepository.findByYear(season));
        log.info("Reconcile season {}: {} players, dryRun={}", season, bySource.size(), dryRun);

        // REASON — no transaction open.
        List<PlayerReconciliation> results = new ArrayList<>();
        List<Double> contestedCvs = new ArrayList<>();   // dry-run distribution input
        Counts counts = new Counts();

        for (Map.Entry<String, List<PlayerProjectionRaw>> entry : bySource.entrySet()) {
            String playerId = entry.getKey();
            List<PlayerProjectionRaw> sourceRows = entry.getValue();

            List<ScoredSource> scored = scoreSources(sourceRows, stick);
            BigDecimal maxPoints = scored.stream()
                    .map(ScoredSource::points)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);

            // Points floor: non-draftable, and guards the CV mean against → 0.
            if (maxPoints.compareTo(pointsFloor) < 0) {
                counts.skipped++;
                continue;
            }

            Double cv = scored.size() >= 2
                    ? DispersionCalculator.coefficientOfVariation(pointsOf(scored))
                    : null;

            if (dryRun) {
                if (cv != null) {
                    contestedCvs.add(cv);
                }
                continue;   // dry-run: record CV only, no staging/model/audit
            }

            results.add(reconcileOne(playerId, season, scored, cv, threshold, counts));
        }

        if (dryRun) {
            CvDistribution distribution = buildDistribution(contestedCvs, threshold);
            return new ReconciliationReport(season, true, 0, 0, 0, 0, counts.skipped, distribution);
        }

        // WRITE (bounded txn) then RE-SCORE the touched players only.
        Set<String> touched = writer.write(season, results);
        int rescored = playerScoringService.computeAndSaveForPlayers(season, touched);
        log.info("Reconcile season {} done: {} consensus, {} llm, {} single, {} fallback, {} skipped; "
                        + "re-scored {} rows for {} players",
                season, counts.consensus, counts.llm, counts.singleSource, counts.fellBack,
                counts.skipped, rescored, touched.size());

        return new ReconciliationReport(season, false, counts.consensus, counts.llm,
                counts.singleSource, counts.fellBack, counts.skipped, null);
    }

    /** Decide one player's mart line and audit row (real run only). */
    private PlayerReconciliation reconcileOne(String playerId, int season, List<ScoredSource> scored,
                                              Double cv, double threshold, Counts counts) {
        int sourceCount = scored.size();
        BigDecimal cvDecimal = cv == null ? null : BigDecimal.valueOf(cv).setScale(4, RoundingMode.HALF_UP);
        String team = representativeTeam(scored);

        // Single source: no dispersion, stage as-is.
        if (sourceCount == 1) {
            counts.singleSource++;
            PlayerProjectionRaw only = scored.get(0).line();
            StagedLine line = new StagedLine(playerId, season, team, only.getSource(),
                    ProjectionStatLine.from(only));
            return new PlayerReconciliation(line, audit(playerId, season, sourceCount, cvDecimal,
                    Route.SINGLE_SOURCE, null, null, only.getSource(), null, null));
        }

        // Agreement (or undefined CV): deterministic median, no model call.
        if (cv == null || cv <= threshold) {
            counts.consensus++;
            StagedLine line = medianLine(playerId, season, team, scored);
            return new PlayerReconciliation(line, audit(playerId, season, sourceCount, cvDecimal,
                    Route.CONSENSUS, null, null, CONSENSUS, null, null));
        }

        // Contested: classify the disagreement, then apply the verdict as a selection rule.
        try {
            Verdict verdict = classifier.classify(toContestedPlayer(playerId, scored));
            counts.llm++;
            return applyVerdict(playerId, season, scored, cvDecimal, team, verdict);
        } catch (VerdictClassificationException e) {
            // One failed model call never aborts the batch: fall back to the median.
            log.warn("Verdict classification failed for {}, falling back to median: {}",
                    playerId, e.getMessage());
            counts.fellBack++;
            StagedLine line = medianLine(playerId, season, team, scored);
            return new PlayerReconciliation(line, audit(playerId, season, sourceCount, cvDecimal,
                    Route.LLM_FALLBACK, null, null, CONSENSUS, null, null));
        }
    }

    private PlayerReconciliation applyVerdict(String playerId, int season, List<ScoredSource> scored,
                                              BigDecimal cvDecimal, String team, Verdict verdict) {
        int sourceCount = scored.size();
        String confidence = verdict.confidence().name();
        String rationale = verdict.rationale();
        String model = classifier.model();

        StagedLine line;
        String chosenSource;
        switch (verdict.verdict()) {
            case FAVOR_HIGH_SOURCE -> {
                PlayerProjectionRaw high = ConsensusBuilder.highest(scored).line();
                chosenSource = high.getSource();
                line = new StagedLine(playerId, season, high.getTeam(), chosenSource,
                        ProjectionStatLine.from(high));
            }
            case FAVOR_LOW_SOURCE -> {
                PlayerProjectionRaw low = ConsensusBuilder.lowest(scored).line();
                chosenSource = low.getSource();
                line = new StagedLine(playerId, season, low.getTeam(), chosenSource,
                        ProjectionStatLine.from(low));
            }
            // TRUST_CONSENSUS and FLAG_UNCERTAIN both write the median; FLAG_UNCERTAIN
            // additionally records the review state on the audit row (route stays LLM).
            default -> {
                chosenSource = CONSENSUS;
                line = medianLine(playerId, season, team, scored);
            }
        }

        return new PlayerReconciliation(line, audit(playerId, season, sourceCount, cvDecimal,
                Route.LLM, verdict.verdict().name(), confidence, chosenSource, rationale, model));
    }

    private StagedLine medianLine(String playerId, int season, String team, List<ScoredSource> scored) {
        return new StagedLine(playerId, season, team, CONSENSUS, ConsensusBuilder.median(scored));
    }

    private List<ScoredSource> scoreSources(List<PlayerProjectionRaw> sourceRows, ScoringFormat stick) {
        List<ScoredSource> scored = new ArrayList<>(sourceRows.size());
        for (PlayerProjectionRaw row : sourceRows) {
            // Throwaway points: measure spread only, never written to the mart.
            BigDecimal points = scoringService.calculate(row, stick).totalPoints();
            scored.add(new ScoredSource(row, points));
        }
        return scored;
    }

    private ContestedPlayer toContestedPlayer(String playerId, List<ScoredSource> scored) {
        Player player = playerRepository.findById(playerId).orElse(null);
        String name = player != null ? player.getFullName() : playerId;
        String position = player != null ? player.getPosition() : null;
        String team = representativeTeam(scored);

        List<ContestedPlayer.Source> sources = scored.stream()
                .map(s -> new ContestedPlayer.Source(s.line().getSource(), s.points(), s.line()))
                .toList();
        ScoredSource high = ConsensusBuilder.highest(scored);
        ScoredSource low = ConsensusBuilder.lowest(scored);

        return new ContestedPlayer(name, position, team, properties.measuringStick().name(),
                sources, high.line().getSource(), high.points(),
                low.line().getSource(), low.points());
    }

    private PlayerProjectionReconciliation audit(String playerId, int season, int sourceCount,
                                                 BigDecimal cv, Route route, String llmVerdict,
                                                 String confidence, String chosenSource,
                                                 String rationale, String model) {
        return PlayerProjectionReconciliation.builder()
                .playerId(playerId)
                .year(season)
                .sourceCount(sourceCount)
                .cv(cv)
                .route(route.name())
                .llmVerdict(llmVerdict)
                .confidence(confidence)
                .chosenSource(chosenSource)
                .rationale(rationale)
                .model(model)
                .build();
    }

    private CvDistribution buildDistribution(List<Double> cvs, double threshold) {
        long[] bucketCounts = new long[5];
        long contested = 0;
        for (double cv : cvs) {
            bucketCounts[bucketIndex(cv)]++;
            if (cv > threshold) {
                contested++;
            }
        }
        List<CvDistribution.Bucket> buckets = List.of(
                new CvDistribution.Bucket("0.00–0.05", bucketCounts[0]),
                new CvDistribution.Bucket("0.05–0.10", bucketCounts[1]),
                new CvDistribution.Bucket("0.10–0.15", bucketCounts[2]),
                new CvDistribution.Bucket("0.15–0.20", bucketCounts[3]),
                new CvDistribution.Bucket("0.20+", bucketCounts[4]));
        return new CvDistribution(buckets, cvs.size(), threshold, contested);
    }

    private int bucketIndex(double cv) {
        if (cv < 0.05) return 0;
        if (cv < 0.10) return 1;
        if (cv < 0.15) return 2;
        if (cv < 0.20) return 3;
        return 4;
    }

    private String representativeTeam(List<ScoredSource> scored) {
        return scored.stream()
                .map(s -> s.line().getTeam())
                .filter(t -> t != null)
                .findFirst()
                .orElse(null);
    }

    private List<BigDecimal> pointsOf(List<ScoredSource> scored) {
        return scored.stream().map(ScoredSource::points).toList();
    }

    private Map<String, List<PlayerProjectionRaw>> groupByPlayer(List<PlayerProjectionRaw> rows) {
        Map<String, List<PlayerProjectionRaw>> grouped = new LinkedHashMap<>();
        for (PlayerProjectionRaw row : rows) {
            grouped.computeIfAbsent(row.getPlayerId(), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /** Mutable run counters, mirroring the ESPN multi-outcome report idiom. */
    private static final class Counts {
        int consensus;
        int llm;
        int singleSource;
        int fellBack;
        int skipped;
    }
}
