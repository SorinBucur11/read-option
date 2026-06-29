package app.readoption.reconciliation;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import app.readoption.playerprojection.PlayerProjection;
import app.readoption.playerprojection.PlayerProjectionId;
import app.readoption.playerprojection.PlayerProjectionRepository;
import app.readoption.playerscoring.PlayerScoring;
import app.readoption.playerscoring.PlayerScoringRepository;
import app.readoption.playerscoring.PlayerScoringService;
import app.readoption.scoring.ScoringFormat;
import app.readoption.scoring.ScoringService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({ReconciliationWriter.class, PlayerScoringService.class, ScoringService.class})
@DisplayName("ReconciliationWriter — upsert idempotency, ADP preservation, targeted re-score")
class ReconciliationWriterIntegrationTest extends AbstractPostgresTest {

    private static final int SEASON = 2026;

    @Autowired private PlayerRepository playerRepository;
    @Autowired private PlayerProjectionRepository projectionRepository;
    @Autowired private PlayerProjectionReconciliationRepository auditRepository;
    @Autowired private PlayerScoringRepository scoringRepository;
    @Autowired private ReconciliationWriter writer;
    @Autowired private PlayerScoringService scoringService;

    @BeforeEach
    void seedPlayers() {
        playerRepository.save(player("P1", "Player One", "WR"));
        playerRepository.save(player("P2", "Player Two", "WR"));
    }

    private static PlayerReconciliation consensusFor(String playerId, BigDecimal recYd) {
        ProjectionStatLine stats = ProjectionStatLine.builder()
                .receivingYards(recYd).receivingTd(new BigDecimal("8.00")).gamesPlayed(17).build();
        StagedLine line = new StagedLine(playerId, SEASON, "KC", "consensus", stats);
        PlayerProjectionReconciliation audit = PlayerProjectionReconciliation.builder()
                .playerId(playerId).year(SEASON).sourceCount(2).cv(new BigDecimal("0.0500"))
                .route(Route.CONSENSUS.name()).chosenSource("consensus").build();
        return new PlayerReconciliation(line, audit);
    }

    @Test
    @DisplayName("re-running the write updates in place — no duplicate mart or audit rows")
    void upsertIsIdempotent() {
        writer.write(SEASON, List.of(consensusFor("P1", new BigDecimal("1000.00"))));
        // fresh entities (isNew=true) on the second pass — the writer must still UPDATE
        writer.write(SEASON, List.of(consensusFor("P1", new BigDecimal("1100.00"))));

        List<PlayerProjection> mart = projectionRepository.findByPlayerId("P1");
        assertThat(mart).hasSize(1);
        assertThat(mart.get(0).getReceivingYards()).isEqualByComparingTo("1100.00");
        assertThat(mart.get(0).getSource()).isEqualTo("consensus");
        assertThat(auditRepository.findByYear(SEASON)).hasSize(1);
    }

    @Test
    @DisplayName("existing mart ADP is carried forward, not wiped by reconciliation")
    void preservesExistingAdp() {
        // ADP arrived from the Phase 1 pipeline before reconciliation ran.
        PlayerProjection existing = PlayerProjection.builder()
                .playerId("P1").year(SEASON).source("rotowire").gamesPlayed(17)
                .adpPpr(new BigDecimal("12.00")).build();
        projectionRepository.saveAndFlush(existing);

        writer.write(SEASON, List.of(consensusFor("P1", new BigDecimal("1000.00"))));

        PlayerProjection result = projectionRepository
                .findById(new PlayerProjectionId("P1", SEASON)).orElseThrow();
        assertThat(result.getSource()).isEqualTo("consensus");          // provenance overwritten
        assertThat(result.getAdpPpr()).isEqualByComparingTo("12.00");   // ADP preserved
    }

    @Test
    @DisplayName("re-score touches only the players the writer returned, leaving others untouched")
    void reScoresTouchedPlayersOnly() {
        // Both players have a mart row, but only P1 is reconciled/touched this run.
        projectionRepository.saveAndFlush(PlayerProjection.builder()
                .playerId("P2").year(SEASON).source("rotowire").gamesPlayed(17)
                .receivingYards(new BigDecimal("500.00")).build());

        Set<String> touched = writer.write(SEASON, List.of(consensusFor("P1", new BigDecimal("1000.00"))));
        scoringService.computeAndSaveForPlayers(SEASON, touched);

        assertThat(touched).containsExactly("P1");
        List<PlayerScoring> scores = scoringRepository.findByYear(SEASON);
        assertThat(scores).isNotEmpty();
        assertThat(scores).allMatch(s -> s.getPlayerId().equals("P1"));
        assertThat(scores).hasSize(ScoringFormat.values().length);   // P1 scored in every format
    }
}
