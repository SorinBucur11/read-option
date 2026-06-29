package app.readoption.reconciliation;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-player reconciliation audit row — answers "why is this projection what it is".
 * Derived/audit table, no FK (mirrors player_scoring). The mart carries provenance
 * only; the review state for FLAG_UNCERTAIN lives here (route=LLM, llmVerdict=FLAG_UNCERTAIN).
 */
@Entity
@Table(name = "player_projection_reconciliation")
@IdClass(PlayerProjectionReconciliationId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerProjectionReconciliation implements Persistable<PlayerProjectionReconciliationId> {

    @Id
    @Column(name = "player_id")
    private String playerId;

    @Id
    private int year;

    @Column(name = "source_count")
    private int sourceCount;

    private BigDecimal cv;                  // null for single-source (no dispersion)

    private String route;                   // CONSENSUS | SINGLE_SOURCE | LLM | LLM_FALLBACK

    @Column(name = "llm_verdict")
    private String llmVerdict;              // enum name, only when route=LLM

    private String confidence;              // only when route=LLM

    @Column(name = "chosen_source")
    private String chosenSource;            // 'consensus' | source name

    @Column(columnDefinition = "text")
    private String rationale;               // only when route=LLM

    private String model;                   // only when route=LLM

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Nullable
    @Override
    @JsonIgnore
    public PlayerProjectionReconciliationId getId() {
        return new PlayerProjectionReconciliationId(playerId, year);
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
    }
}
