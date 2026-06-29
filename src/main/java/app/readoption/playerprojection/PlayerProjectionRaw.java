package app.readoption.playerprojection;

import app.readoption.scoring.Scorable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "player_projection_raw")
@IdClass(PlayerProjectionRawId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerProjectionRaw implements Persistable<PlayerProjectionRawId>, Scorable {

    @Id
    @Column(name = "player_id")
    private String playerId;

    @Id
    private int year;

    @Id
    private String source;

    private String team;
    private Integer gamesPlayed;

    // NUMERIC(7,2) since V7: the landing table preserves each source's fractional
    // projection exactly, so integer rounding never injects noise into the
    // cross-source dispersion signal. games_played stays Integer (a game count).
    private BigDecimal passingYards;
    private BigDecimal passingTd;
    private BigDecimal interceptions;
    private BigDecimal rushingYards;
    private BigDecimal rushingTd;
    private BigDecimal receptions;
    private BigDecimal receivingYards;
    private BigDecimal receivingTd;
    private BigDecimal fumblesLost;
    private BigDecimal twoPtConv;

    private BigDecimal adp;
    private String adpFormat;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_payload", columnDefinition = "jsonb")
    private String sourcePayload;

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
    public PlayerProjectionRawId getId() {
        return new PlayerProjectionRawId(playerId, year, source);
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