package app.readoption.team;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
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
import org.springframework.lang.Nullable;

/**
 * One team-week row; each game appears twice (once per team) — matches both the
 * ESPN per-team delivery shape and every read pattern. Landing semantics: no FK,
 * delete-and-reload per {@code (team, season)}. Both {@code team} and
 * {@code opponent} are <b>Sleeper</b> abbrevs — the ESPN→Sleeper crosswalk happens
 * once at the write boundary, so all read-side joins speak one vocabulary.
 *
 * <p>Assigned composite key, so the Persistable pattern applies (17-row bulk
 * insert per team without a SELECT per entity).
 */
@Entity
@Table(name = "team_schedule")
@IdClass(TeamScheduleId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamSchedule implements Persistable<TeamScheduleId> {

    @Id
    private String team;

    @Id
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int season;

    @Id
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private int week;

    @Column(nullable = false)
    private String opponent;

    @Column(name = "is_home", nullable = false)
    private boolean isHome;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Nullable
    @Override
    @JsonIgnore
    public TeamScheduleId getId() {
        return new TeamScheduleId(team, season, week);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public void markExisting() {
        this.isNew = false;
    }
}
