package app.readoption.draft;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DraftPickRepository extends JpaRepository<DraftPick, DraftPickId> {

    List<DraftPick> findBySessionIdOrderByOverallPickNo(long sessionId);

    // drafted-status batch lookup for tool candidate lists: one query, not one per candidate
    List<DraftPick> findBySessionIdAndPlayerIdIn(long sessionId, Collection<String> playerIds);

    // friendly-error path for can't-draft-twice; carries the pick that took the player.
    // uq_draft_pick_player remains the backstop at flush.
    Optional<DraftPick> findBySessionIdAndPlayerId(long sessionId, String playerId);

    // server-assigned sequencing: next pick = max + 1 (empty on a fresh session)
    @Query("SELECT MAX(p.overallPickNo) FROM DraftPick p WHERE p.sessionId = :sessionId")
    Optional<Integer> findMaxOverallPickNo(@Param("sessionId") long sessionId);
}
