package app.readoption.customization;

import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One confirmed league configuration — the resolved output of the customization
 * confirm gate, and the only thing that flow ever writes. Scoring rules are typed
 * columns (engine-consumed, validate-on-write); {@code tactics} is JSONB on the
 * typed {@link DraftTactics} (LLM-consumed, never queried), same idiom as
 * {@code source_payload}. No FK — there is no user table yet.
 */
@Entity
@Table(name = "league_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeagueConfig implements Persistable<Long> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ----- resolved scoring rules (typed; the resolver's output, never the LLM's) -----

    @Enumerated(EnumType.STRING)
    @Column(name = "reception_format")
    private ReceptionFormat receptionFormat;

    private BigDecimal passingTdPoints;
    private BigDecimal interceptionPoints;
    private BigDecimal teReceptionBonus;

    // ----- roster -----

    private int teamCount;
    private int qbSlots;
    private int rbSlots;
    private int wrSlots;
    private int teSlots;
    private int flexSlots;

    @Convert(converter = FlexEligibleConverter.class)
    @Column(name = "flex_eligible")
    private Set<Position> flexEligible;

    private int superflexSlots;
    private int benchSlots;

    // ----- playoffs (nullable: only when stated; consumed in Phase 4) -----

    private Integer playoffTeams;
    private Integer playoffStartWeek;
    private Integer playoffEndWeek;

    // ----- draft tactics (LLM-consumed, never queried) -----

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private DraftTactics tactics;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Nullable
    @Override
    @JsonIgnore
    public Long getId() {
        return id;
    }

    @Override
    @JsonIgnore
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
