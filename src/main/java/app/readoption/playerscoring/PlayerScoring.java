package app.readoption.playerscoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "player_scoring")
@IdClass(PlayerScoringId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerScoring implements Persistable<PlayerScoringId> {

    @Id
    @Column(name = "player_id")
    private String playerId;

    @Id
    private int year;

    @Id
    @Column(name = "scoring_format")
    private String scoringFormat;

    @Column(name = "total_points", nullable = false)
    private BigDecimal totalPoints;

    @Column(name = "points_per_game", nullable = false)
    private BigDecimal pointsPerGame;

    @Column(name = "games_played", nullable = false)
    private int gamesPlayed;

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
    public PlayerScoringId getId() {
        return new PlayerScoringId(playerId, year, scoringFormat);
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