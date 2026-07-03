package app.readoption.draft;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;

/**
 * One observed pick. Insert-only — no update path anywhere in the service. The
 * composite key is assigned (session + server-computed pick number), so the
 * Persistable pattern applies: without it Spring Data would SELECT-before-INSERT
 * on every save. {@code uq_draft_pick_player} in the DB is the final arbiter of
 * can't-draft-twice; the service check exists for the friendly error.
 *
 * <p>No {@code team_no} field: snake-draft team assignment is arithmetic over
 * {@code (overallPickNo, teamCount)} via {@link SnakeOrder} — persisting it would
 * store a derivation that can drift from its source.
 */
@Entity
@Table(name = "draft_pick")
@IdClass(DraftPickId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftPick implements Persistable<DraftPickId> {

    @Id
    @Column(name = "session_id")
    private long sessionId;

    @Id
    @Column(name = "overall_pick_no")
    private int overallPickNo;

    @Column(name = "player_id")
    private String playerId;

    @Column(name = "picked_at")
    private LocalDateTime pickedAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Nullable
    @Override
    @JsonIgnore
    public DraftPickId getId() {
        return new DraftPickId(sessionId, overallPickNo);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    protected void onCreate() {
        if (this.pickedAt == null) {
            this.pickedAt = LocalDateTime.now();
        }
    }
}
