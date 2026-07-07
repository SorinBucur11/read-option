package app.readoption.team;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

/**
 * Thin NFL team reference row, seeded by V14 (exactly 32 — no OAK: the Sleeper blob
 * still carries the stale pre-relocation code, which is source noise, not a team).
 * The PK is the <b>Sleeper</b> abbreviation — canonical because it must join to
 * {@code player.team}; {@code espnAbbrev} is the crosswalk column (single divergence:
 * Sleeper {@code WAS} / ESPN {@code WSH}).
 *
 * <p>{@code byeWeek} is derived data: rebuilt by every schedule sync from the
 * schedule gap, never hand-entered. Null means "not derivable from what landed" —
 * a partial fetch must produce an absent bye, never a wrong one.
 */
@Entity
@Table(name = "nfl_team")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NflTeam implements Persistable<String> {

    @Id
    private String abbrev;

    @Column(name = "espn_abbrev", nullable = false)
    private String espnAbbrev;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "bye_week")
    private Integer byeWeek;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Override
    @JsonIgnore
    public String getId() {
        return abbrev;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markExisting() {
        this.isNew = false;
    }

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
