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

    private Integer passingYards;
    private Integer passingTd;
    private Integer interceptions;
    private Integer rushingYards;
    private Integer rushingTd;
    private Integer receptions;
    private Integer receivingYards;
    private Integer receivingTd;
    private Integer fumblesLost;
    private Integer twoPtConv;

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