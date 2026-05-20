package app.readoption.player;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
@Table(name = "player")
public class Player implements Persistable<String> {

    @Id
    private String id;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String position;
    private String team;
    private Integer age;

    @Column(name = "years_exp")
    private Integer yearsExp;

    private String status;
    private Boolean active;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    private boolean isNew = true;

    protected Player() {}

    public Player(String id, String firstName, String lastName, String fullName) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.fullName = fullName;
        this.createdAt = LocalDateTime.now();
    }

    @Override
    public String getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    public void markExisting() { this.isNew = false; }

    // rest of getters and setters stay the same
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getFullName() { return fullName; }
    public String getPosition() { return position; }
    public String getTeam() { return team; }
    public Integer getAge() { return age; }
    public Integer getYearsExp() { return yearsExp; }
    public String getStatus() { return status; }
    public Boolean getActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setPosition(String position) { this.position = position; }
    public void setTeam(String team) { this.team = team; }
    public void setAge(Integer age) { this.age = age; }
    public void setYearsExp(Integer yearsExp) { this.yearsExp = yearsExp; }
    public void setStatus(String status) { this.status = status; }
    public void setActive(Boolean active) { this.active = active; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}