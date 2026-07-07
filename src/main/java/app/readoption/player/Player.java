package app.readoption.player;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;

@Entity
@Table(name = "player")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
    private String espnId;

    // Depth chart + injury: Sleeper's RAW vocabulary (e.g. LWR/RWR/SWR), no enums —
    // the consumer is the LLM; normalization graduates only when Java must branch.
    @Column(name = "depth_chart_position")
    private String depthChartPosition;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "depth_chart_order")
    private Integer depthChartOrder;

    @Column(name = "injury_status")
    private String injuryStatus;

    @Column(name = "injury_body_part")
    private String injuryBodyPart;

    @Column(name = "injury_notes")
    private String injuryNotes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Override
    public String getId() {
        return id;
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