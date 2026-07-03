package app.readoption.reconciliation;

import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionId;
import app.readoption.playerprojection.PlayerProjectionRaw;
import app.readoption.playerprojection.PlayerProjectionRawRepository;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerprojection.RotowireProjectionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The WRITE phase: a single bounded transaction that upserts the staged mart lines and
 * the audit rows. Kept on its own bean so {@code @Transactional} actually applies (no
 * self-call proxy bypass) and so the transaction never spans the model calls that
 * happen in the REASON phase upstream.
 */
@Component
public class ReconciliationWriter {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWriter.class);
    private static final int CHUNK = 500;

    private final PlayerProjectionRepository projectionRepository;
    private final PlayerProjectionReconciliationRepository auditRepository;
    private final PlayerProjectionRawRepository rawRepository;

    public ReconciliationWriter(PlayerProjectionRepository projectionRepository,
                                PlayerProjectionReconciliationRepository auditRepository,
                                PlayerProjectionRawRepository rawRepository) {
        this.projectionRepository = projectionRepository;
        this.auditRepository = auditRepository;
        this.rawRepository = rawRepository;
    }

    /**
     * Upsert mart lines + audit rows; returns the touched player ids so the caller can
     * re-score exactly them. Reconciliation is the only writer of player_projections.
     */
    @Transactional
    public Set<String> write(int season, List<PlayerReconciliation> results) {
        Map<PlayerProjectionId, PlayerProjection> existingMart = projectionRepository.findByYear(season)
                .stream()
                .collect(Collectors.toMap(PlayerProjection::getId, Function.identity()));
        Set<String> existingAudit = auditRepository.findPlayerIdsByYear(season);
        Map<String, PlayerProjectionRaw> rotowireByPlayer =
                rawRepository.findByYearAndSource(season, RotowireProjectionMapper.SOURCE)
                        .stream()
                        .collect(Collectors.toMap(PlayerProjectionRaw::getPlayerId, Function.identity()));

        List<PlayerProjection> martRows = new ArrayList<>();
        List<PlayerProjectionReconciliation> auditRows = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();

        for (PlayerReconciliation result : results) {
            martRows.add(toProjection(result.line(), existingMart, rotowireByPlayer));

            PlayerProjectionReconciliation audit = result.audit();
            if (existingAudit.contains(audit.getPlayerId())) {
                audit.markExisting();
            }
            auditRows.add(audit);
            touched.add(result.line().playerId());
        }

        saveInChunks(martRows, projectionRepository::saveAll);
        saveInChunks(auditRows, auditRepository::saveAll);

        log.info("Reconciliation write: {} mart rows, {} audit rows for season {}",
                martRows.size(), auditRows.size(), season);
        return touched;
    }

    private PlayerProjection toProjection(StagedLine staged,
                                          Map<PlayerProjectionId, PlayerProjection> existingMart,
                                          Map<String, PlayerProjectionRaw> rotowireByPlayer) {
        ProjectionStatLine s = staged.stats();
        PlayerProjection projection = PlayerProjection.builder()
                .playerId(staged.playerId())
                .year(staged.year())
                .source(staged.chosenSource())
                .team(staged.team())
                .gamesPlayed(s.getGamesPlayed())
                .passingYards(s.getPassingYards())
                .passingTd(s.getPassingTd())
                .interceptions(s.getInterceptions())
                .rushingYards(s.getRushingYards())
                .rushingTd(s.getRushingTd())
                .receptions(s.getReceptions())
                .receivingYards(s.getReceivingYards())
                .receivingTd(s.getReceivingTd())
                .fumblesLost(s.getFumblesLost())
                .twoPtConv(s.getTwoPtConv())
                .build();

        // ADP is an observed market fact, never derived and never verdict-following:
        // copy the three per-format values verbatim from the rotowire raw row for every
        // player written, regardless of route. No rotowire row (or null in raw) → null.
        PlayerProjectionRaw rotowire = rotowireByPlayer.get(staged.playerId());
        if (rotowire != null) {
            projection.setAdpStd(rotowire.getAdpStd());
            projection.setAdpHalfPpr(rotowire.getAdpHalfPpr());
            projection.setAdpPpr(rotowire.getAdpPpr());
        }

        if (existingMart.containsKey(projection.getId())) {
            projection.markExisting();   // upsert: UPDATE not INSERT on re-run
        }
        return projection;
    }

    private <T> void saveInChunks(List<T> rows, java.util.function.Consumer<List<T>> save) {
        for (int from = 0; from < rows.size(); from += CHUNK) {
            save.accept(rows.subList(from, Math.min(from + CHUNK, rows.size())));
        }
    }
}
