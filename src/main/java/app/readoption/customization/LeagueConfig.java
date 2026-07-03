package app.readoption.customization;

import app.readoption.scoring.LeagueSettings;
import app.readoption.scoring.Position;
import app.readoption.scoring.ReceptionFormat;
import app.readoption.scoring.ScoringRules;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * One confirmed league configuration — the resolved output of the customization
 * confirm gate, and the only thing that flow ever writes. Scoring rules are typed
 * columns (engine-consumed, validate-on-write); {@code tactics} is JSONB on the
 * typed {@link DraftTactics} (LLM-consumed, never queried), same idiom as
 * {@code source_payload}. No FK — there is no user table yet.
 *
 * <p>Deliberately <b>not</b> {@code Persistable}: that pattern defeats Spring Data's
 * exists-check on <i>assigned</i> ids, but this table uses {@code IDENTITY} generation
 * and only ever inserts (one row per confirmed config, no upsert) — the null pre-insert
 * id already marks the entity as new. The id also belongs in confirm's JSON response.
 */
@Entity
@Table(name = "league_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeagueConfig {

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

    /**
     * The confirmed row as the engine's resolved scoring config — the same precedent
     * as {@link app.readoption.scoring.ScoringFormat#toScoringRules()}. Pure mapping,
     * no defaults invented: the row was already resolved at confirm, so a null here
     * is corruption and throws rather than silently defaulting.
     */
    public ScoringRules toScoringRules() {
        if (receptionFormat == null || passingTdPoints == null
                || interceptionPoints == null || teReceptionBonus == null) {
            throw new IllegalStateException(
                    "league_config " + id + " is missing resolved scoring columns — "
                            + "a confirmed row can never have null scoring rules");
        }
        return ScoringRules.of(
                receptionFormat.pointsPerReception(),
                passingTdPoints,
                interceptionPoints,
                teReceptionBonus);
    }

    /** The confirmed row's roster shape as the engine's value object. Same null rule. */
    public LeagueSettings toLeagueSettings() {
        if (flexEligible == null) {
            throw new IllegalStateException(
                    "league_config " + id + " has null flex_eligible — "
                            + "a confirmed row always carries the resolved set");
        }
        return new LeagueSettings(teamCount, qbSlots, rbSlots, wrSlots, teSlots,
                flexSlots, flexEligible, superflexSlots, benchSlots);
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
