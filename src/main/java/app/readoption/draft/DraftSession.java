package app.readoption.draft;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One live (or finished) draft. Everything but {@code status} is frozen at creation:
 * {@code totalRounds} is computed from the confirmed config's roster shape at start,
 * so a later config row can't change a running draft's length mid-flight.
 *
 * <p>Deliberately <b>not</b> {@code Persistable} — IDENTITY generation already marks
 * new rows (same reasoning as {@code league_config}), and the id must serialize on
 * create. Mutable in exactly one way: status.
 */
@Entity
@Table(name = "draft_session")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "league_config_id")
    private long leagueConfigId;   // logical ref, no FK (config table convention)

    private int season;
    private int teamCount;
    private int userSlot;
    private int totalRounds;

    @Setter
    @Enumerated(EnumType.STRING)
    private DraftStatus status;

    /**
     * Binds this session to a live Sleeper draft (Phase 5.0). NULL = manual
     * session; non-null makes the sync the single writer — manual picks are
     * rejected with 409. UNIQUE in the DB: relinking a synced draft resumes this
     * session, never creates a sibling. Frozen at creation like the rest.
     */
    @Column(name = "sleeper_draft_id", updatable = false)
    private String sleeperDraftId;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
