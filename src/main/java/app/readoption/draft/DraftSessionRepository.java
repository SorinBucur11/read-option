package app.readoption.draft;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DraftSessionRepository extends JpaRepository<DraftSession, Long> {

    // sync relink/resume lookup; uq_draft_session_sleeper_draft_id guarantees at most one
    Optional<DraftSession> findBySleeperDraftId(String sleeperDraftId);
}
