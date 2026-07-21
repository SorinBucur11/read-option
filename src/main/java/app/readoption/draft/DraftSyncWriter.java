package app.readoption.draft;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The WRITE phase of the Sleeper draft sync, in its own bean so the
 * {@code @Transactional} proxy applies (the {@code PlayerNewsWriter} precedent:
 * the orchestrator loops HTTP fetches and must never hold a transaction across
 * them). All validation happens upstream in {@link DraftSyncService}; rows that
 * reach this class are survivors of the gates.
 */
@Component
public class DraftSyncWriter {

    private final DraftSessionRepository sessionRepository;
    private final DraftPickRepository pickRepository;

    public DraftSyncWriter(DraftSessionRepository sessionRepository,
                           DraftPickRepository pickRepository) {
        this.sessionRepository = sessionRepository;
        this.pickRepository = pickRepository;
    }

    /** Persists the session created at the first {@code drafting} observation. */
    @Transactional
    public DraftSession createSession(DraftSession session) {
        return sessionRepository.save(session);
    }

    /**
     * One transaction per poll's batch of new picks, in {@code pick_no} order.
     * {@code picked_at} is left null here so {@code @PrePersist} stamps it —
     * i.e. it records <b>observation time</b>: Sleeper picks carry no timestamp,
     * so the closest honest fact is "when this poll saw it".
     */
    @Transactional
    public void insertPicks(List<DraftPick> picks) {
        pickRepository.saveAll(picks);
    }

    /** Flips the session to COMPLETE once Sleeper reports the draft complete. */
    @Transactional
    public void markComplete(DraftSession session) {
        session.setStatus(DraftStatus.COMPLETE);
        sessionRepository.save(session);
    }
}
