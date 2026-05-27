package app.readoption.playerscoring;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "player_scoring")
@IdClass(PlayerScoringId.class)
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
    private boolean isNew = true;

    public PlayerScoring() {}

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

    public String getPlayerId() {
        return playerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
    }

    public String getScoringFormat() {
        return scoringFormat;
    }

    public void setScoringFormat(String scoringFormat) {
        this.scoringFormat = scoringFormat;
    }

    public BigDecimal getTotalPoints() {
        return totalPoints;
    }

    public void setTotalPoints(BigDecimal totalPoints) {
        this.totalPoints = totalPoints;
    }

    public BigDecimal getPointsPerGame() {
        return pointsPerGame;
    }

    public void setPointsPerGame(BigDecimal pointsPerGame) {
        this.pointsPerGame = pointsPerGame;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(int gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

}