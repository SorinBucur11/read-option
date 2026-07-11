package app.readoption.news;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * One landed news item — the LANDING table row: verbatim from the source
 * (HTML and all; cleaning is a derived-side concern in the embedding build),
 * insert-only, no FK to {@code player} per the landing-table convention.
 * There is deliberately no {@code @PreUpdate}: rows are never updated, so a
 * second sight of the same {@code (source, news_id)} is skipped, never merged.
 *
 * <p>Timestamps are {@link Instant}s over TIMESTAMPTZ columns — the grounding
 * invariant extends into the time dimension (audit A-4): {@code published} is the
 * fact every retrieved citation must carry.
 *
 * <p>Assigned composite key, so the Persistable pattern applies (bulk insert
 * without a SELECT per entity; the writer's existence check owns dedup).
 */
@Entity
@Table(name = "player_news")
@IdClass(PlayerNewsId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlayerNews implements Persistable<PlayerNewsId> {

    @Id
    private String source;

    @Id
    @Column(name = "news_id")
    private String newsId;

    @Column(name = "player_id", nullable = false)
    private String playerId;

    @Column(name = "espn_player_id", nullable = false)
    private long espnPlayerId;

    @Column(nullable = false)
    private String headline;

    private String story;

    @Column(nullable = false)
    private Instant published;

    @Column(name = "last_modified")
    private Instant lastModified;

    @Column(nullable = false)
    private boolean premium;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_payload", columnDefinition = "jsonb", nullable = false)
    private String sourcePayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    @JsonIgnore
    @Builder.Default
    private boolean isNew = true;

    @Nullable
    @Override
    @JsonIgnore
    public PlayerNewsId getId() {
        return new PlayerNewsId(source, newsId);
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
        this.createdAt = Instant.now();
    }
}
