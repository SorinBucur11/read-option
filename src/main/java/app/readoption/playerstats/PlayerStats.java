package app.readoption.playerstats;

import app.readoption.player.Player;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.time.LocalDateTime;

@Entity
@Table(name = "player_stats")
@IdClass(PlayerStatsId.class)
public class PlayerStats implements Persistable<PlayerStatsId> {

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
    private int gamesPlayed;
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
    private Integer twoPtConv;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    private boolean isNew = true;

    protected PlayerStats() {}

    public PlayerStats(String playerId, int year) {
        this.playerId = playerId;
        this.year = year;
    }

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

    public Player getPlayer() {
        return player;
    }

    public void setPlayer(Player player) {
        this.player = player;
    }

    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    public int getGames() {
        return games;
    }

    public void setGames(int games) {
        this.games = games;
    }

    public int getGamesPlayed() {
        return gamesPlayed;
    }

    public void setGamesPlayed(int gamesPlayed) {
        this.gamesPlayed = gamesPlayed;
    }

    public Integer getPassAttempts() {
        return passAttempts;
    }

    public void setPassAttempts(Integer passAttempts) {
        this.passAttempts = passAttempts;
    }

    public Integer getPassesCompleted() {
        return passesCompleted;
    }

    public void setPassesCompleted(Integer passesCompleted) {
        this.passesCompleted = passesCompleted;
    }

    public Integer getPassingYards() {
        return passingYards;
    }

    public void setPassingYards(Integer passingYards) {
        this.passingYards = passingYards;
    }

    public Integer getPassingTd() {
        return passingTd;
    }

    public void setPassingTd(Integer passingTd) {
        this.passingTd = passingTd;
    }

    public Integer getInterceptions() {
        return interceptions;
    }

    public void setInterceptions(Integer interceptions) {
        this.interceptions = interceptions;
    }

    public Integer getRushingAttempts() {
        return rushingAttempts;
    }

    public void setRushingAttempts(Integer rushingAttempts) {
        this.rushingAttempts = rushingAttempts;
    }

    public Integer getRushingYards() {
        return rushingYards;
    }

    public void setRushingYards(Integer rushingYards) {
        this.rushingYards = rushingYards;
    }

    public Integer getRushingTd() {
        return rushingTd;
    }

    public void setRushingTd(Integer rushingTd) {
        this.rushingTd = rushingTd;
    }

    public Integer getTargets() {
        return targets;
    }

    public void setTargets(Integer targets) {
        this.targets = targets;
    }

    public Integer getReceptions() {
        return receptions;
    }

    public void setReceptions(Integer receptions) {
        this.receptions = receptions;
    }

    public Integer getReceivingYards() {
        return receivingYards;
    }

    public void setReceivingYards(Integer receivingYards) {
        this.receivingYards = receivingYards;
    }

    public Integer getReceivingTd() {
        return receivingTd;
    }

    public void setReceivingTd(Integer receivingTd) {
        this.receivingTd = receivingTd;
    }

    public Integer getTwoPtConv() {
        return twoPtConv;
    }

    public void setTwoPtConv(Integer twoPtConv) {
        this.twoPtConv = twoPtConv;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}