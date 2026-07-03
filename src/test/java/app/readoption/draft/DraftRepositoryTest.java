package app.readoption.draft;

import app.readoption.AbstractPostgresTest;
import app.readoption.player.PlayerRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static app.readoption.TestFixtures.player;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)   // real container, real V12 constraints
@DisplayName("draft_session + draft_pick persistence — IDENTITY id, audit stamps, uq_draft_pick_player backstop")
class DraftRepositoryTest extends AbstractPostgresTest {

    @Autowired private DraftSessionRepository sessionRepository;
    @Autowired private DraftPickRepository pickRepository;
    @Autowired private PlayerRepository playerRepository;
    @Autowired private TestEntityManager entityManager;

    @BeforeEach
    void seedPlayers() {
        playerRepository.save(player("P1", "Player One", "RB"));
        playerRepository.save(player("P2", "Player Two", "WR"));
    }

    private DraftSession saveSession() {
        return sessionRepository.save(DraftSession.builder()
                .leagueConfigId(42L).season(2026)
                .teamCount(10).userSlot(8).totalRounds(13)
                .status(DraftStatus.ACTIVE)
                .build());
    }

    @Test
    @DisplayName("session round-trip: generated id, audit timestamps, status update stamps updated_at")
    void sessionRoundTrip() {
        DraftSession saved = saveSession();
        entityManager.flush();

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        saved.setStatus(DraftStatus.COMPLETE);
        entityManager.flush();
        entityManager.clear();

        DraftSession reloaded = sessionRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DraftStatus.COMPLETE);
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(reloaded.getCreatedAt());
    }

    @Test
    @DisplayName("pick insert: Persistable INSERT path, picked_at stamped by @PrePersist")
    void pickInsert() {
        DraftSession session = saveSession();

        DraftPick pick = pickRepository.save(DraftPick.builder()
                .sessionId(session.getId()).overallPickNo(1).playerId("P1").build());
        entityManager.flush();
        entityManager.clear();

        DraftPick reloaded = pickRepository
                .findById(new DraftPickId(session.getId(), 1)).orElseThrow();
        assertThat(reloaded.getPlayerId()).isEqualTo("P1");
        assertThat(reloaded.getPickedAt()).isNotNull();
        assertThat(pick.getPickedAt()).isNotNull();
    }

    @Test
    @DisplayName("duplicate (session, player) violates uq_draft_pick_player at flush — the backstop is real")
    void duplicatePlayerViolatesUniqueConstraint() {
        DraftSession session = saveSession();
        pickRepository.save(DraftPick.builder()
                .sessionId(session.getId()).overallPickNo(1).playerId("P1").build());
        entityManager.flush();

        // saveAndFlush so the violation surfaces through the repository proxy,
        // translated to Spring's DataIntegrityViolationException — the exact
        // exception DraftService's backstop catch relies on. Assert the Hibernate
        // cause carries the constraint NAME: that is what the service matches on,
        // so the real dialect must be proven to populate it.
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> pickRepository.saveAndFlush(DraftPick.builder()
                        .sessionId(session.getId()).overallPickNo(2).playerId("P1").build()))
                .withCauseInstanceOf(ConstraintViolationException.class)
                .satisfies(ex -> assertThat(
                        ((ConstraintViolationException) ex.getCause()).getConstraintName())
                        .isEqualToIgnoringCase("uq_draft_pick_player"));
    }

    @Test
    @DisplayName("findMaxOverallPickNo: empty on a fresh session, max on a populated one")
    void findMaxOverallPickNo() {
        DraftSession fresh = saveSession();
        DraftSession populated = saveSession();
        pickRepository.save(DraftPick.builder()
                .sessionId(populated.getId()).overallPickNo(1).playerId("P1").build());
        pickRepository.save(DraftPick.builder()
                .sessionId(populated.getId()).overallPickNo(2).playerId("P2").build());
        entityManager.flush();

        assertThat(pickRepository.findMaxOverallPickNo(fresh.getId())).isEmpty();
        assertThat(pickRepository.findMaxOverallPickNo(populated.getId())).isEqualTo(Optional.of(2));
    }
}
