package app.readoption.playerstats;

import app.readoption.player.Player;
import app.readoption.scoring.Scorable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;

@Entity
@Table(name = "player_stats")
@IdClass(PlayerStatsId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerStats implements Persistable<PlayerStatsId>, Scorable {

    @Id
    @Column(name = "player_id")
    private String playerId;

    @Id
    private int year;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", insertable = false, updatable = false)
    @JsonIgnore
    private Player player;

    private String team;
    private int games;
    private Integer gamesPlayed;
    private Integer passAttempts;
    private Integer passesCompleted;
    private Integer passingYards;
    private Integer passingTd;
    private Integer interceptions;
    private Integer rushingAttempts;
    private Integer rushingYards;
    private Integer rushingTd;
    private Integer targets;
    private Integer receptions;
    private Integer receivingYards;
    private Integer receivingTd;
    private Integer fumblesLost;
    private Integer twoPtConv;

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
    public PlayerStatsId getId() {
        return new PlayerStatsId(playerId, year);
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